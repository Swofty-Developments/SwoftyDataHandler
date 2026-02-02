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

## Storage Backends

| Backend | Persistence | Multi-server | Event listeners |
|---------|------------|-------------|-----------------|
| `InMemoryDataStorage` | No | No | Yes |
| `FileDataStorage` | Yes (local files) | No | **No** |
| `RedisDataStorage` | Yes (Redis) | Yes | Yes |

`FileDataStorage` does not support event listeners. Calling `subscribe` on a `DataAPI` backed by file storage throws `UnsupportedOperationException`.

```java
// File storage with custom format and extension
new FileDataStorage(basePath, new JsonFormat(), ".json")
new FileDataStorage(basePath, new BinaryFormat(), ".dat")

// Redis with connection pool
JedisPool pool = new JedisPool("localhost", 6379);
new RedisDataStorage(pool)
new RedisDataStorage(pool, "myapp:data") // custom key prefix
```

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

// Direct transaction on linked data
api.transactionDirect(islandId, ISLAND, tx -> {
    tx.update(ISLAND_BANK, bank -> bank - 1000L);
    return null;
});
```

## Event Listeners

Subscribe to data changes. **Requires a storage backend that supports listeners** (Redis or InMemory -- not File).

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

// Expiration events
api.subscribeExpiration(ACTIVE_BOOST, (player, field, expiredValue) -> {
    System.out.println(player + "'s boost expired: " + expiredValue);
});
```

### Cross-Server Events (Redis)

When using `RedisDataStorage`, events are automatically distributed across all server instances via Redis Pub/Sub. A change on Server A fires listeners on Server B.

```java
// Server A
DataAPI apiA = new DataAPIImpl(new RedisDataStorage("redis-host", 6379));
apiA.set(player, COINS, 1000);

// Server B -- listener fires automatically
DataAPI apiB = new DataAPIImpl(new RedisDataStorage("redis-host", 6379));
apiB.subscribe(COINS, (p, old, nw) -> {
    // This fires when Server A changes the value
});
```

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
new JsonFormat()   // human-readable, good for debugging
new BinaryFormat() // compact, good for production
```

Both implement `DataFormat` and can be used with any storage backend.

## Lifecycle

Always shut down the API when done:

```java
api.shutdown(); // stops expiration timers, closes Pub/Sub subscribers
```

For Redis storage, also close the storage:

```java
RedisDataStorage storage = new RedisDataStorage("localhost", 6379);
DataAPI api = new DataAPIImpl(storage);
// ...
api.shutdown();
storage.close();
```

## License

See [LICENSE](LICENSE) for details.
