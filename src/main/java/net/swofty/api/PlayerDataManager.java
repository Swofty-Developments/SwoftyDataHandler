package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.PlayerField;
import net.swofty.data.DataFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;
import net.swofty.storage.LeaderboardIndex;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

// Internal to net.swofty.api — reach it through DataAPI / DataAPIImpl, not directly.
class PlayerDataManager {
    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final ConcurrentHashMap<UUID, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
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
        return locks.computeIfAbsent(player, k -> new Object());
    }

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

    public <T> void set(UUID player, PlayerField<T> field, T value) {
        Validation.validate(field, value);
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            setFieldValue(player, field, value);
            eventBus.firePlayerDataChanged(field, player, oldValue, value);
        }
    }

    public <T> void update(UUID player, PlayerField<T> field, UnaryOperator<T> updater) {
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            T newValue = updater.apply(oldValue);
            Validation.validate(field, newValue);
            setFieldValue(player, field, newValue);
            eventBus.firePlayerDataChanged(field, player, oldValue, newValue);
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

    <T> void setFieldValue(UUID player, DataField<T> field, T value) {
        DataContainer container = getContainer(player);
        // Warm the backing document first so serialize() merges over it and never
        // drops fields that were never read this session.
        ensureDocumentLoaded(player, container);
        container.set(field, value);
        if (autoPersist) {
            persist(player);
        }
    }

    private void ensureDocumentLoaded(UUID player, DataContainer container) {
        if (!container.isDocumentLoaded()) {
            container.loadDocument(format, storage.load("players", player.toString()));
        }
    }

    void persist(UUID player) {
        DataContainer container = cache.get(player);
        if (container != null) {
            byte[] bytes = container.serialize(format);
            storage.save("players", player.toString(), bytes);
            container.markPersisted(bytes);
            updateLeaderboards(player, container);
        }
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
                container.loadDocument(format, storage.load("players", player.toString()));
            }
        }
    }

    /** Persists pending changes for a player if the cache holds unsaved edits. */
    public void flush(UUID player) {
        synchronized (getLock(player)) {
            DataContainer container = cache.get(player);
            if (container != null && container.isDirty()) {
                persist(player);
            }
        }
    }

    /** Flushes pending changes then evicts the player from this node's cache. */
    public void unload(UUID player) {
        synchronized (getLock(player)) {
            DataContainer container = cache.get(player);
            if (container != null && container.isDirty()) {
                persist(player);
            }
            cache.remove(player);
        }
        locks.remove(player);
    }

    public boolean isLoaded(UUID player) {
        return cache.containsKey(player);
    }

    public Set<UUID> loadedPlayers() {
        return new HashSet<>(cache.keySet());
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
    <T> void applyRemote(DataField<T> field, UUID player, T newValue) {
        DataContainer container = cache.get(player);
        if (container == null) return;
        synchronized (getLock(player)) {
            container = cache.get(player);
            if (container == null) return;
            container.applyRemote(field, newValue, format);
        }
    }

    public List<String> listPlayerIds() {
        return storage.listIds("players");
    }
}
