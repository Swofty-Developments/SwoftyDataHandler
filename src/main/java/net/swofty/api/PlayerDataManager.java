package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.LinkType;
import net.swofty.PlayerField;
import net.swofty.data.DataFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;
import net.swofty.storage.LeaderboardIndex;
import net.swofty.storage.SaveRequest;
import net.swofty.storage.SaveResult;
import net.swofty.storage.StorageKey;
import net.swofty.storage.VersionedData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

// Internal to net.swofty.api — reach it through DataAPI / DataAPIImpl, not directly.
class PlayerDataManager {
    private static final System.Logger LOGGER = System.getLogger(PlayerDataManager.class.getName());

    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final ConcurrentHashMap<UUID, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, EntitySession> sessions = new ConcurrentHashMap<>();
    // Optional custom score functions, keyed by field. Numeric fields need no entry here — they
    // are scored automatically — so leaderboards require no registration in the common case.
    private final ConcurrentHashMap<String, ToDoubleFunction<?>> scorers = new ConcurrentHashMap<>();
    private final boolean autoPersist;

    public PlayerDataManager(DataStorage storage, DataFormat format, EventBus eventBus) {
        this(storage, format, eventBus, true);
    }

    public PlayerDataManager(DataStorage storage, DataFormat format, EventBus eventBus, boolean autoPersist) {
        this.storage = storage;
        this.format = format;
        this.eventBus = eventBus;
        this.autoPersist = autoPersist;
    }

    public Object getLock(UUID player) {
        return session(player);
    }

    private EntitySession session(UUID player) { return sessions.computeIfAbsent(player, ignored -> new EntitySession()); }

    DataContainer getContainer(UUID player) {
        return cache.computeIfAbsent(player, id -> new DataContainer());
    }

    public <T> T get(UUID player, PlayerField<T> field, ExpirationManager expiration) {
        synchronized (getLock(player)) {
            if (field instanceof ExpiringField<T> exp && expiration.isExpired(player, exp)) {
                return field.defaultValue();
            }
            return getFieldValue(player, field);
        }
    }

    public <T> SaveResult set(UUID player, PlayerField<T> field, T value) {
        Validation.validate(field, value);
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            SaveResult saved = setFieldValue(player, field, value);
            eventBus.firePlayerDataChanged(field, player, oldValue, value, saved.saved() ? saved.version() : -1L);
            return saved;
        }
    }

    public <T> SaveResult update(UUID player, PlayerField<T> field, UnaryOperator<T> updater) {
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            T newValue = updater.apply(oldValue);
            Validation.validate(field, newValue);
            SaveResult saved = setFieldValue(player, field, newValue);
            eventBus.firePlayerDataChanged(field, player, oldValue, newValue, saved.saved() ? saved.version() : -1L);
            return saved;
        }
    }

    @SuppressWarnings("unchecked")
    <T> T getFieldValue(UUID player, DataField<T> field) {
        DataContainer container = getContainer(player);
        if (!container.has(field.fullKey())) {
            ensureDocumentLoaded(player, container);
            container.ensureField(field, format);
        }
        return container.get(field);
    }

    <T> SaveResult setFieldValue(UUID player, DataField<T> field, T value) {
        DataContainer container = getContainer(player);
        // Warm the backing document first so serialize() merges over it and never
        // drops fields that were never read this session.
        ensureDocumentLoaded(player, container);
        container.set(field, value);
        if (autoPersist) {
            return persist(player);
        }
        return SaveResult.unchanged("players", player.toString(), container.documentVersion());
    }

    private void ensureDocumentLoaded(UUID player, DataContainer container) {
        if (!container.isDocumentLoaded()) {
            VersionedData loaded = storage.loadVersioned("players", player.toString());
            container.loadDocument(format, loaded.data(), loaded.version());
        }
    }

    SaveResult persist(UUID player) {
        DataContainer container = cache.get(player);
        if (container != null) {
            byte[] bytes = container.serialize(format);
            SaveResult result = storage.save("players", player.toString(), bytes).toCompletableFuture().join();
            container.markPersisted(bytes, result.version());
            updateLeaderboards(player, container);
            return result;
        }
        return SaveResult.unchanged("players", player.toString(), 0);
    }

    // ---- Leaderboard indexing ----------------------------------------------
    //
    // Leaderboards need no registration. The first time a field is ranked, its index is built by
    // a one-time scan (ensureLeaderboardBuilt); from then on the index EXISTS in shared storage,
    // and every node maintains it on write via updateScoreIfPresent. A field that is never ranked
    // has no index, so its writes cost nothing. Numeric fields are scored automatically; a custom
    // scorer is only needed to rank a non-numeric field.

    private LeaderboardIndex leaderboardIndex() {
        return storage instanceof LeaderboardIndex index ? index : null;
    }

    private LeaderboardIndex requireLeaderboardIndex() {
        LeaderboardIndex index = leaderboardIndex();
        if (index == null) {
            throw new IllegalStateException("Storage " + storage.getClass().getSimpleName()
                    + " does not support leaderboards; use a LeaderboardIndex-capable storage"
                    + " (e.g. RedisDataStorage or InMemoryDataStorage)");
        }
        return index;
    }

    /** Optional: register a score function so a non-numeric field can be ranked. */
    public <T> void trackLeaderboard(PlayerField<T> field, ToDoubleFunction<T> scorer) {
        requireLeaderboardIndex();
        scorers.put(field.fullKey(), scorer);
    }

    @SuppressWarnings("unchecked")
    private Double scoreOf(String fullKey, Object value) {
        if (value == null) return null;
        ToDoubleFunction<Object> scorer = (ToDoubleFunction<Object>) scorers.get(fullKey);
        if (scorer != null) return scorer.applyAsDouble(value);
        if (value instanceof Number number) return number.doubleValue();
        return null; // not rankable without a scorer
    }

    // Maintains only leaderboards that already exist, so unranked fields cost nothing.
    private void updateLeaderboards(UUID player, DataContainer container) {
        LeaderboardIndex index = leaderboardIndex();
        if (index == null) return;
        for (Map.Entry<String, Object> entry : container.rawData().entrySet()) {
            Double score = scoreOf(entry.getKey(), entry.getValue());
            if (score != null) {
                index.updateScoreIfPresent(entry.getKey(), player.toString(), score);
            }
        }
    }

    /** Builds the index on first use by scanning existing players once; a no-op once it exists. */
    <T> void ensureLeaderboardBuilt(PlayerField<T> field) {
        LeaderboardIndex index = requireLeaderboardIndex();
        if (index.leaderboardExists(field.fullKey())) return;
        rebuildLeaderboard(field);
    }

    /** Rebuilds a field's index from stored data. Called automatically on first rank; also public. */
    public <T> void rebuildLeaderboard(PlayerField<T> field) {
        LeaderboardIndex index = requireLeaderboardIndex();
        for (String id : storage.listIds("players")) {
            UUID player = UUID.fromString(id);
            T value = getFieldValue(player, field);
            Double score = scoreOf(field.fullKey(), value);
            if (score == null) {
                throw new IllegalStateException("Leaderboard field '" + field.fullKey()
                        + "' is not numeric; register a score function with trackLeaderboard(field, scorer)");
            }
            index.updateScore(field.fullKey(), id, score);
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    /** Warms the player's whole document into this node's cache in a single storage read. */
    public void load(UUID player) {
        synchronized (getLock(player)) {
            DataContainer container = getContainer(player);
            if (!container.isDocumentLoaded()) {
                VersionedData loaded = storage.loadVersioned("players", player.toString());
                container.loadDocument(format, loaded.data(), loaded.version());
            }
        }
    }

    public CompletionStage<Void> loadAsync(UUID player, Executor executor) {
        EntitySession session = session(player);
        synchronized (session) {
            if (cache.get(player) != null && cache.get(player).isDocumentLoaded()) {
                return CompletableFuture.completedFuture(null);
            }
            if (session.loading != null) return session.loading;
            CompletableFuture<Void> future = new CompletableFuture<>();
            session.loading = future;
            try {
                executor.execute(() -> {
                    try { load(player); future.complete(null); }
                    catch (Throwable failure) { future.completeExceptionally(failure); }
                    finally { synchronized (session) { if (session.loading == future) session.loading = null; } }
                });
            } catch (Throwable failure) {
                session.loading = null;
                future.completeExceptionally(failure);
            }
            return future;
        }
    }

    private void awaitLoad(UUID player) {
        CompletableFuture<Void> loading = session(player).loading;
        if (loading != null) loading.join();
    }

    /** Persists pending changes for a player if the cache holds unsaved edits. */
    public SaveResult flush(UUID player) {
        awaitLoad(player);
        synchronized (getLock(player)) {
            DataContainer container = cache.get(player);
            if (container != null && container.isDirty()) {
                SaveResult saved = persist(player);
                eventBus.firePlayerSnapshotSaved(player, saved.version());
                return saved;
            }
            DataContainer current = cache.get(player);
            return SaveResult.unchanged("players", player.toString(), current == null ? 0 : current.documentVersion());
        }
    }

    public CompletionStage<SaveResult> flushAsync(UUID player, Executor executor) {
        CompletableFuture<Void> loading = session(player).loading;
        CompletionStage<Void> before = loading == null ? CompletableFuture.completedFuture(null) : loading;
        return before.thenApplyAsync(ignored -> flush(player), executor);
    }

    /** Flushes pending changes then evicts the player from this node's cache. */
    public SaveResult unload(UUID player) {
        awaitLoad(player);
        SaveResult result;
        synchronized (getLock(player)) {
            DataContainer container = cache.get(player);
            if (container != null && container.isDirty()) {
                result = persist(player);
                eventBus.firePlayerSnapshotSaved(player, result.version());
            } else {
                result = SaveResult.unchanged("players", player.toString(), container == null ? 0 : container.documentVersion());
            }
            cache.remove(player);
        }
        return result;
    }

    public CompletionStage<SaveResult> unloadAsync(UUID player, Executor executor) {
        CompletableFuture<Void> loading = session(player).loading;
        CompletionStage<Void> before = loading == null ? CompletableFuture.completedFuture(null) : loading;
        return before.thenApplyAsync(ignored -> unload(player), executor);
    }

    public boolean isLoaded(UUID player) {
        return cache.containsKey(player);
    }

    public Set<UUID> loadedPlayers() {
        return new HashSet<>(cache.keySet());
    }

    /**
     * Rereads a player's document from storage, discarding this node's materialised fields. Only
     * for a caller holding the entity's exclusive (cross-node) lock: pending local writes are
     * flushed first so nothing buffered is lost, and the reread then picks up whatever another
     * node wrote since this node last looked. A player not cached here needs nothing.
     */
    void refresh(UUID player) {
        synchronized (getLock(player)) {
            DataContainer container = cache.get(player);
            if (container == null) return;
            if (container.isDirty()) {
                persist(player);
            }
            VersionedData loaded = storage.loadVersioned("players", player.toString());
            container.reload(loaded.data(), loaded.version());
        }
    }

    /** Flushes every cached player. Used on shutdown so deferred writes are not lost. */
    public void flushAll() {
        for (UUID player : cache.keySet()) {
            flush(player);
        }
    }

    /**
     * Applies a change that originated on another node to the locally cached container,
     * without re-persisting or re-firing events. Only touches players that are currently
     * loaded here, so it never resurrects an evicted or never-loaded entity.
     */
    <T> boolean applyRemote(DataField<T> field, UUID player, T newValue, long version) {
        DataContainer container = cache.get(player);
        if (container == null) return true;
        synchronized (getLock(player)) {
            container = cache.get(player);
            if (container == null) return true;
            return container.applyRemote(field, newValue, version, format);
        }
    }

    List<SaveRequest> snapshotDocuments() {
        List<SaveRequest> requests = new ArrayList<>();
        for (UUID player : cache.keySet()) {
            synchronized (getLock(player)) {
                DataContainer container = cache.get(player);
                if (container != null) requests.add(new SaveRequest(new StorageKey("players", player.toString()),
                        container.serialize(format)));
            }
        }
        return requests;
    }

    long currentVersion(UUID player) {
        DataContainer container = cache.get(player);
        return container == null ? 0L : container.documentVersion();
    }

    void applyRemoteSnapshot(UUID player, long version) {
        DataContainer container = cache.get(player);
        if (container == null) return;
        synchronized (getLock(player)) {
            container = cache.get(player);
            if (container == null || container.isDirty() || version <= container.documentVersion()) return;
            VersionedData loaded = storage.loadVersioned("players", player.toString());
            if (loaded.version() >= version) container.reload(loaded.data(), loaded.version());
        }
    }

    /**
     * Reads a player's stored link key straight out of their document, so a node that never ran
     * {@code link()} itself can still resolve the link. The key is decoded with the link type's key
     * codec, which is how {@code link()} stored it; a document that predates the field, or whose
     * value cannot be decoded, resolves to no link rather than failing the read.
     */
    <K> K loadLinkKey(UUID player, LinkType<K> type) {
        PlayerField<K> playerField = type.playerField();
        if (playerField == null) return null;
        DataField<K> ref = new SimpleFieldRef<>(playerField.fullKey(), type.keyCodec());
        try {
            synchronized (getLock(player)) {
                return getFieldValue(player, ref);
            }
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not read link key for " + type.name() + " from player " + player, e);
            return null;
        }
    }

    public List<String> listPlayerIds() {
        return storage.listIds("players");
    }
}
