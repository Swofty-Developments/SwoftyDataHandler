# SwoftyDataHandler


[<img src="https://discordapp.com/assets/e4923594e694a21542a489471ecffa50.svg" alt="Discord" height="55" />](https://discord.swofty.net)

A reactive, type-safe data management library for Java applications. Built for Minecraft servers but usable anywhere you need per-entity data with shared/linked fields, expiration, validation, events, transactions, and bulk operations.

## Installation

**Gradle:**
```groovy
dependencies {
    implementation 'net.swofty:SwoftyDataHandler:<version>'

    // Only if using Redis storage
    implementation 'redis.clients:jedis:5.2.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>net.swofty</groupId>
    <artifactId>SwoftyDataHandler</artifactId>
    <version>VERSION</version>
</dependency>

<!-- Only if using Redis storage -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.2.0</version>
</dependency>
```

## Quick Start

```java
// 1. Pick a storage backend
DataStorage storage = new InMemoryDataStorage();          // testing / ephemeral
DataStorage storage = new FileDataStorage(path, new JsonFormat()); // single-server
DataStorage storage = new RedisDataStorage("localhost", 6379);     // multi-server

// 2. Create the API
DataAPI api = new DataAPIImpl(storage);

// 3. Define fields
PlayerField<Integer> COINS = PlayerField.create("economy", "coins", Codecs.INT, 0);

// 4. Use it
UUID player = UUID.randomUUID();
api.set(player, COINS, 500);
api.update(player, COINS, c -> c + 100);
int coins = api.get(player, COINS); // 600
```

Writes are synchronous and durable by default: when `set` returns, the document is in storage. A
storage failure is thrown to the caller rather than reported to a future nobody is watching.

The lifecycle operations also have async forms, which share one in-flight operation per player:

```java
api.loadAsync(player);       // concurrent calls for this player share one in-flight load
api.flushAsync(player);      // queued behind an in-flight load for the same player
api.unloadAsync(player);
```

## Storage Backends

| Backend | Persistence | Shared across servers | Indexed leaderboards |
|---------|------------|----------------------|----------------------|
| `InMemoryDataStorage` | No | No | Yes |
| `FileDataStorage` | Yes (local files) | No | No |
| `RedisDataStorage` | Yes (Redis) | Yes | Yes |
| `MongoDataStorage` | Yes (MongoDB) | Yes | No |

Event listeners are fired by the API, not by the storage, so they work with **every** backend --
including `FileDataStorage`. What storage decides is whether other servers can see the same data;
delivering events *between* servers is a separate concern, handled by a `PubSubHandler`
(see [Cross-Server Events](#cross-server-events-redis)).

```java
// File storage with custom format and extension
new FileDataStorage(basePath, new JsonFormat(), ".json")

// Redis with connection pool
JedisPool pool = new JedisPool("localhost", 6379);
new RedisDataStorage(pool)
new RedisDataStorage(pool, "myapp:data") // custom key prefix

// Mongo, from a client or an existing database handle
new MongoDataStorage(mongoClient, "myapp")
```

Storage ownership is explicit. Constructors default to `StorageOwnership.BORROWED`; choose
`OWNED` when `DataAPI.shutdown()` should also close the storage (and the distributed lock, if it is
closeable) along with its client/pool:

```java
DataAPI api = new DataAPIImpl(storage, StorageOwnership.OWNED);
```

### Concurrent writes to one document

A player or shared entity is one document, and two nodes can legitimately write different fields of
it at the same time. Each node serialises its own view over the document it last read, so a plain
write would erase whatever the other node had just put there.

Every write is therefore conditional on the document version the node last read
(`DataStorage.saveIfVersion`), and losing that race is not an error: the node rereads the winner's
document, replays only its own unsaved fields onto it and writes again. Both fields survive, with
no coordination, no lock and no pub/sub message required.

| Backend | Version source | Comparison |
|---------|----------------|------------|
| `RedisDataStorage` | `<prefix>:version:<type>:<id>` counter | one Lua script: compare, bump, write, index |
| `MongoDataStorage` | `version` field | `findOneAndUpdate` filtered on the version, `$inc` |
| `InMemoryDataStorage` | per-key counter | compare and write under the key's monitor |
| `FileDataStorage` | `<id><ext>.version` sidecar | compare and write under the instance monitor (one JVM only) |

Retries are bounded (8 attempts, with a short randomised backoff). A document under permanent
contention resolves as last-writer-wins and says so at `WARNING`, because blocking the calling
thread forever is worse than losing a write loudly.

A storage written against 1.4.x keeps working unchanged: `loadVersioned` and `saveIfVersion` are
defaulted, and a backend that does not implement them reports "unversioned", which degrades every
write to the 1.4.x last-writer-wins behaviour instead of pretending to be atomic.

## Player Fields

Per-player data with type safety, default values, and optional validation.

```java
PlayerField<Integer> COINS = PlayerField.create("economy", "coins", Codecs.INT, 0);
PlayerField<String> NAME  = PlayerField.create("profile", "name", Codecs.STRING, "");

// With validation
PlayerField<Integer> LEVEL = PlayerField.<Integer>builder("rpg", "level")
        .codec(Codecs.INT)
        .defaultValue(1)
        .validator(Validators.range(1, 100))
        .build();

api.set(player, COINS, 500);
api.update(player, COINS, c -> c + 100);
int coins = api.get(player, COINS);
```

All fields use a `namespace:key` format internally (e.g. `economy:coins`) to prevent collisions between systems.

`defaultValue(x)` hands back exactly `x` every time it is read, which is what a shared immutable
default should do — reading a field nobody has written is the hot path and must not allocate or
decode. A **mutable** default must not be shared between entities, so declare it as a factory
instead:

```java
PlayerField<List<String>> QUESTS = PlayerField.<List<String>>builder("profile", "quests")
        .codec(Codecs.list(Codecs.STRING))
        .defaultFactory(ArrayList::new)   // a fresh list per miss
        .build();
```

## Linked Fields (Shared Data)

Data shared across multiple players through a common key (guild bank, island level, party settings).

```java
// 1. Define the player field that holds the link key
PlayerField<UUID> ISLAND_ID = PlayerField.create(
        "skyblock", "island_id", Codecs.nullable(Codecs.UUID), null);

// 2. Define the link type
LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);

// 3. Define linked fields
// The first argument is the namespace (for storage key organization), not the link type.
// e.g. "island" + "level" -> storage key "island:level"
LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create(
        "island", "level", Codecs.INT, 1, ISLAND);
LinkedField<UUID, Long> ISLAND_BANK = LinkedField.create(
        "island", "bank", Codecs.LONG, 0L, ISLAND);

// 4. Link players and use shared data
UUID islandId = UUID.randomUUID();
api.link(player1, ISLAND, islandId);
api.link(player2, ISLAND, islandId);

api.set(player1, ISLAND_BANK, 1000L);
long bank = api.get(player2, ISLAND_BANK); // 1000 -- same data

// Direct access by link key -- useful when you have the key but not a player UUID,
// e.g. updating island data from a scheduled task or admin command
api.setDirect(islandId, ISLAND_LEVEL, 5);
```

`link` persists the key on the player's own document, so a server that never ran `link` for that
player (a freshly started node, or the one they just moved to) recovers it from storage on first
use: `getLinkKey` and every player-based linked read or write resolve without re-linking.

## Codecs

Built-in codecs for serialization:

| Codec | Type |
|-------|------|
| `Codecs.INT` | `Integer` |
| `Codecs.LONG` | `Long` |
| `Codecs.FLOAT` | `Float` |
| `Codecs.DOUBLE` | `Double` |
| `Codecs.BOOL` | `Boolean` |
| `Codecs.STRING` | `String` |
| `Codecs.UUID` | `UUID` |
| `Codecs.INSTANT` | `Instant` |

Compound codecs:

```java
Codecs.list(Codecs.STRING)                   // List<String>
Codecs.set(Codecs.UUID)                      // Set<UUID>
Codecs.map(Codecs.STRING, Codecs.INT)        // Map<String, Integer>
Codecs.nullable(Codecs.UUID)                 // UUID (nullable)
```

### Versioned Codecs

Handle schema changes with automatic migration chains:

```java
VersionedCodec<PlayerStats> STATS_CODEC = VersionedCodec.builder(
        3,
        reader -> new PlayerStats(reader.readInt(), reader.readInt(), reader.readString()),
        (writer, stats) -> {
            writer.writeInt(stats.kills());
            writer.writeInt(stats.deaths());
            writer.writeString(stats.rank());
        }
)
    .legacyReader(1, reader -> new V1Stats(reader.readInt()))
    .legacyReader(2, reader -> new V2Stats(reader.readInt(), reader.readInt()))
    .migrate(1, 2, v1 -> new V2Stats(v1.kills(), 0))
    .migrate(2, 3, v2 -> new PlayerStats(v2.kills(), v2.deaths(), "unranked"))
    .build();
```

Reading v1 data automatically chains: v1 -> v2 -> v3.

## Validation

Composable validators that throw `ValidationException` on failure:

```java
PlayerField<Integer> SCORE = PlayerField.<Integer>builder("game", "score")
        .codec(Codecs.INT)
        .defaultValue(0)
        .validator(Validators.nonNegative())
        .build();

PlayerField<String> NAME = PlayerField.<String>builder("profile", "name")
        .codec(Codecs.STRING)
        .defaultValue("")
        .validator(Validators.maxLength(32))
        .build();

// Chain validators with .and()
Validator<Integer> strict = Validators.nonNegative().and(Validators.range(0, 10000));
```

Built-in validators: `Validators.nonNegative()`, `Validators.range(min, max)`, `Validators.maxLength(max)`.

## Expiring Fields

Fields with automatic TTL:

```java
ExpiringField<String> ACTIVE_BOOST = ExpiringField.<String>expiringBuilder("game", "boost")
        .codec(Codecs.STRING)
        .defaultValue(null)
        .defaultTtl(Duration.ofMinutes(30))
        .build();

api.set(player, ACTIVE_BOOST, "double_xp");                   // uses default 30min TTL
api.set(player, ACTIVE_BOOST, "double_xp", Duration.ofHours(1)); // custom TTL
api.extend(player, ACTIVE_BOOST, Duration.ofMinutes(15));      // add time
api.getTimeRemaining(player, ACTIVE_BOOST);                    // Optional<Duration>
api.isExpired(player, ACTIVE_BOOST);                           // boolean

// Expired fields return their default value on get()
```

Expiring linked fields work the same way:

```java
ExpiringLinkedField<UUID, Integer> ISLAND_BUFF =
        ExpiringLinkedField.<UUID, Integer>expiringBuilder("island", "buff", ISLAND)
                .codec(Codecs.INT)
                .defaultValue(0)
                .defaultTtl(Duration.ofHours(2))
                .build();
```

## Transactions

Atomic multi-field operations with rollback on abort:

```java
// With return value
int newBalance = api.transaction(player, tx -> {
    int coins = tx.get(COINS);
    int price = 500;
    if (coins < price) {
        tx.abort(); // rolls back all changes
    }
    tx.set(COINS, coins - price);
    tx.update(ITEMS, items -> items + 1);
    return coins - price;
});

// Without return value
api.transaction(player, tx -> {
    tx.update(COINS, c -> c + 100);
    tx.set(NAME, "NewName");
});

// Direct transaction on linked data -- operates on the key you pass, no player involved
long remaining = api.transactionDirect(islandId, ISLAND, tx -> {
    long bank = tx.get(ISLAND_BANK) - 1000L;
    tx.set(ISLAND_BANK, bank);
    return bank;
});
```

A direct transaction is bound to the one link key it was given: linked fields of that link type
resolve against it, and player fields are not available inside it (there is no player to resolve
them for). Committed transactions fire the same events a direct `set` would, so listeners run and
other nodes refresh their caches.

## Event Listeners

Subscribe to data changes. Listeners are fired by the API itself, so they work with any storage
backend. A listener that throws is logged and skipped -- the remaining listeners still run.

```java
// Player field changes
api.subscribe(COINS, (player, oldValue, newValue) -> {
    System.out.println(player + ": " + oldValue + " -> " + newValue);
});

// Linked field changes (includes all affected players)
api.subscribe(ISLAND_LEVEL, (islandId, oldLevel, newLevel, affectedPlayers) -> {
    System.out.println("Island " + islandId + " leveled up, affecting " + affectedPlayers.size() + " players");
});

// Link/unlink events
api.subscribe(ISLAND, new LinkChangeListener<UUID>() {
    public void onLinked(UUID player, LinkType<UUID> type, UUID key) {
        System.out.println(player + " joined island " + key);
    }
    public void onUnlinked(UUID player, LinkType<UUID> type, UUID previousKey) {
        System.out.println(player + " left island " + previousKey);
    }
});

// Expiration events -- fired by the expiration sweep with the value that expired
api.subscribeExpiration(ACTIVE_BOOST, (player, field, expiredValue) -> {
    System.out.println(player + "'s boost expired: " + expiredValue);
});

// Linked expiration events also carry the affected members
api.subscribeExpiration(ISLAND_BUFF, (islandId, field, expiredValue, memberIds) -> {
    System.out.println("Island " + islandId + " lost its buff: " + expiredValue);
});
```

### Cross-Server Events (Redis)

Events are distributed across server instances by a `PubSubHandler`, which you pass explicitly --
storage alone does not distribute them. With one supplied, a change on Server A fires listeners on
Server B, and Server B's cached copy of the changed field is refreshed in place.

```java
JedisPool pool = new JedisPool("redis-host", 6379);

// Server A
DataAPI apiA = new DataAPIImpl(new RedisDataStorage(pool), new JsonFormat(), new RedisPubSubHandler(pool));
apiA.set(player, COINS, 1000);

// Server B -- listener fires automatically
DataAPI apiB = new DataAPIImpl(new RedisDataStorage(pool), new JsonFormat(), new RedisPubSubHandler(pool));
apiB.subscribe(COINS, (p, old, nw) -> {
    // This fires when Server A changes the value
});
```

Without a `PubSubHandler` (e.g. `new DataAPIImpl(storage)`) listeners are local to the process that
made the change. `KeyDBPubSubHandler` is the same handler for KeyDB.

**Ordering.** Messages can arrive out of order, so each field of each entity carries the document
version its write produced, and a receiver drops an event older than one it has already applied to
that same field. The gate is per field, not per document: one transaction writes several fields at
a single document version, and two nodes routinely write different fields at versions that arrive
in either order — a per-document gate would silently drop all but the first field of every
transaction. A node that defers its writes has no durable version to order by, so its events are
published unversioned and always delivered; its later `flush` publishes a snapshot notice that
makes peers reread the whole document.

The per-entity ordering state is dropped when the entity is unloaded, and capped so a node that
only ever *hears* about entities cannot grow without bound.

## Bulk Operations

### Leaderboards

```java
// Top 10 by natural ordering (descending)
List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 10);
for (LeaderboardEntry<Integer> entry : top) {
    System.out.println("#" + entry.rank() + " " + entry.playerId() + ": " + entry.value());
}

// Custom comparator
api.getTop(COINS, 10, Comparator.naturalOrder()); // ascending

// Paginated (1-indexed pages)
Page<LeaderboardEntry<Integer>> page = api.getTopPaged(COINS, 1, 50);
page.content();       // entries for this page
page.page();          // current page number
page.totalPages();    // total pages
page.totalElements(); // total entries

// Linked leaderboards
api.getTopLinked(ISLAND_LEVEL, 10);
```

### Queries

```java
// Find players matching a condition
List<UUID> rich = api.query(COINS, coins -> coins > 10000);

// Count matching players
int count = api.count(COINS, coins -> coins > 10000);

// Query linked data
List<UUID> activeIslands = api.queryLinked(ISLAND_LEVEL, level -> level > 5);
```

### Bulk Updates

```java
// Update all players
int updated = api.updateAll(COINS, c -> c + 100); // daily bonus

// Update matching players
int reset = api.updateWhere(COINS, c -> c < 0, c -> 0); // fix negative balances
```

## Data Formats

Two serialization formats are included:

```java
new JsonFormat()   // human-readable, and the format the API stores documents in
new BinaryFormat() // compact, sequential -- for reading and writing values yourself
```

Entity documents hold many fields at once, so the format backing a `DataAPI` must support
whole-document access (`readRaw`/`writeRaw`). `JsonFormat` does; `BinaryFormat` is purely
sequential and does not, so passing it to a `DataAPI` makes writes throw
`UnsupportedOperationException`. Use `BinaryFormat` with codecs directly when you need a compact
encoding of a single value, and leave the API on `JsonFormat`.

## Multi-Server Lifecycle

Across a fleet of servers a player (or a shared entity such as an island) is authoritative on
exactly one node at a time. The lifecycle API lets a node warm that data into its cache before
use and evict it afterwards, so a later visit to any node is never served a stale value.

```java
// Warm a player's whole document into this node in a single storage read.
api.load(player);                       // synchronous
api.loadAsync(player, executor);        // CompletableFuture<Void>
api.isLoaded(player);                   // boolean

// Persist pending changes and drop the player from this node's cache.
api.unload(player);                     // flush + evict
api.flush(player);                      // flush without evicting

// Shared/linked entities have the same lifecycle.
api.loadLink(ISLAND, islandId);
api.unloadLink(ISLAND, islandId);
```

**Deleting a shared entity.** Unlinking the last member does not delete the entity — members come
and go, and the document is shared state that outlives them — so an island or coop that is actually
disbanded has to be ended explicitly, or its document stays in storage forever:

```java
api.deleteLink(ISLAND, islandId);
```

That removes the document from storage, unlinks every player still linked to it (clearing the link
key on their own documents and firing the usual unlink events), drops any expirations registered
against it, and tells every other node to unlink its players and evict its cached copy. Afterwards
`getDirect` on that key reads the fields' defaults.

This is the primitive a proxy uses to implement "load the player's data on the target server
*before* moving them there": the proxy asks the destination to `load(player)`, waits for the
ack, then connects the player. Because the origin calls `unload(player)` on disconnect, the
destination always starts from fresh storage.

**Deferred persistence.** By default every write is flushed immediately. Pass `autoPersist = false`
to buffer a whole play session in the cache and write it back once, on `flush`/`unload`
(`shutdown` flushes everything so nothing is lost):

```java
DataAPI api = new DataAPIImpl(storage, new JsonFormat(), pubSub, /* autoPersist */ false);
```

**Cache coherency.** With a distributed event bus, a change made on another node to an entity that
is currently loaded here updates the local view in place, so subscribed fields never go stale
while a player is online. Eviction on `unload` handles the general case. Applying a peer's field
does not make this node claim the peer's document version — it saw one field, not the whole
document — so its next write still compare-and-sets against the version it genuinely holds.

A snapshot notice (from a peer's `flush`/`unload`) replaces the whole document here, links included,
so the link registry is re-derived from it: a player another node moved out of a coop stops
resolving to that coop here too.

## Distributed Locking

Transactions guard a JVM-local lock by default, which only serialises threads within one process.
Supply a `DistributedLock` to get true cross-node mutual exclusion — required when the same shared
entity can be mutated from more than one server (e.g. a coop bank):

```java
DistributedLock lock = new RedisDistributedLock(jedisPool);   // or InMemoryDistributedLock for one node
DataAPI api = new DataAPIImpl(storage, new JsonFormat(), pubSub, true, lock);

// Transactions now take a cross-node lock keyed by the entity, in addition to the local monitor.
api.transactionDirect(coopId, COOP, tx -> { tx.update(BANK, b -> b - 1000L); return null; });

// The same primitive is available for app-level critical sections:
try (var handle = api.lock("coop-transfer:" + coopId, Duration.ofSeconds(5))) {
    // ... multi-entity critical section ...
}
```

The Redis implementation uses `SET NX PX` with a compare-and-delete release, so a lock is only
released by its owner and a lease bounds a crashed holder. A held lock renews its lease in the
background from a single shared scheduler, so work that legitimately outlives the lease does not
lose it halfway through.

Renewal can still fail — the process stalls, the connection breaks, the key is force-deleted — and
`Handle.isValid()` / `Handle.ensureValid()` report that. **`ensureValid()` is best-effort**: it says
the lease was still held a moment ago, not that it will still be held while the next write lands.
`Handle.fencingToken()` is published for callers that need a real guarantee and can reject stale
writes at the storage layer; **this library does not enforce the token on its own writes**. What
does protect the data across nodes is the compare-and-set write path above, which is enforced
unconditionally.

How long a transaction (or a `DISTRIBUTED` write, below) waits for an entity's lock is
configurable, and defaults to 10 seconds:

```java
DataAPI api = new DataAPIImpl(storage, new JsonFormat(), pubSub, true, lock,
        StorageOwnership.BORROWED, Duration.ofSeconds(3));
```

### Locked single-field writes

A transaction is the general tool, but a single read-modify-write across nodes can ask for the same
protection directly:

```java
api.update(player, COINS, c -> c + 100, UpdateMode.DISTRIBUTED);
api.updateDirect(coopId, BANK, b -> b - 1000L, UpdateMode.DISTRIBUTED);
```

`UpdateMode.LOCAL` (the default for the overloads without a mode) takes only the JVM-local monitor.
`DISTRIBUTED` takes the entity's cross-node lock, rereads the entity under it, then writes.

A distributed lock is not reentrant, so a `DISTRIBUTED` write **inside a transaction on the same
entity** rides the lock the transaction already holds instead of deadlocking against itself. Asking
for a *different* entity's lock while holding one throws `IllegalStateException` immediately rather
than waiting for a timeout, because two nodes doing that in opposite orders deadlock each other
until both leases expire; take `api.lock(key, timeout)` explicitly, in a fixed order, if you need
more than one entity.

A lock alone does not prevent a lost update: it serialises writers, but a node could still take it
and then compute its new value from a cached copy a peer had already overwritten. So with a
`DistributedLock` configured, a transaction rereads the entity from storage once it holds the lock,
and the body sees what is really stored -- even if the pub/sub message announcing the peer's change
has not arrived yet, or there is no pub/sub at all. Deferred writes are flushed before that reread,
so nothing buffered is lost.

## Indexed Leaderboards

Leaderboards are index-backed and **self-registering — no setup for numeric fields**. The first time
you rank a field its index is built from existing data in one scan; from then on every node maintains
it on write, and ranking reads only the requested slice. This requires a `LeaderboardIndex`-capable
storage (`RedisDataStorage`, backed by sorted sets, or `InMemoryDataStorage` for single-node/tests):

```java
api.getTop(COINS, 10);         // just works — O(log N + page), no registration
api.getTopPaged(COINS, 1, 50);
```

Only a **non-numeric** field needs a score function, since a sorted set ranks by a number:

```java
api.trackLeaderboard(NAME, String::length); // rank players by name length
```

`rebuildLeaderboard(field)` forces a rebuild from stored data if you ever need it. Storage backends
that don't maintain an index (e.g. `FileDataStorage`) throw on ranking rather than silently scanning;
`getTop(field, limit, comparator)` remains as the explicit scan-based escape hatch for ad-hoc custom
orderings.

## Lifecycle

Always shut down the API when done:

```java
api.shutdown(); // flushes deferred writes, stops expiration timers, closes Pub/Sub subscribers
```

`shutdown()` is idempotent, and every stage runs even if an earlier one fails, so one broken
subsystem cannot leak the threads and connections held by the rest.

For Redis storage, also close the storage:

```java
RedisDataStorage storage = new RedisDataStorage("localhost", 6379);
DataAPI api = new DataAPIImpl(storage);
// ...
api.shutdown();
storage.close();
```

Or hand ownership to the API and let `shutdown()` (or try-with-resources — `DataAPI` is
`AutoCloseable`) close both:

```java
try (DataAPI api = new DataAPIImpl(storage, StorageOwnership.OWNED)) {
    // ...
}
```

## License

See [LICENSE](LICENSE) for details.
