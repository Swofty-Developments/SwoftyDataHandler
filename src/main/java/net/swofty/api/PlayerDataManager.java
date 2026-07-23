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

public class PlayerDataManager {
    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final ConcurrentHashMap<UUID, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrackedLeaderboard<?>> tracked = new ConcurrentHashMap<>();
    private final boolean autoPersist;

    private record TrackedLeaderboard<T>(PlayerField<T> field, ToDoubleFunction<T> scorer) {}

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

    private LeaderboardIndex leaderboardIndex() {
        return storage instanceof LeaderboardIndex index ? index : null;
    }

    boolean isLeaderboardTracked(String fullKey) {
        return tracked.containsKey(fullKey);
    }

    public <T> void trackLeaderboard(PlayerField<T> field, ToDoubleFunction<T> scorer) {
        if (leaderboardIndex() == null) {
            throw new IllegalStateException("Storage " + storage.getClass().getSimpleName()
                    + " does not maintain a leaderboard index; use a LeaderboardIndex-capable storage"
                    + " (e.g. RedisDataStorage or InMemoryDataStorage)");
        }
        tracked.put(field.fullKey(), new TrackedLeaderboard<>(field, scorer));
    }

    private void updateLeaderboards(UUID player, DataContainer container) {
        LeaderboardIndex index = leaderboardIndex();
        if (index == null || tracked.isEmpty()) return;
        for (TrackedLeaderboard<?> t : tracked.values()) {
            // Only index a field once it is materialised this session, so we never write a
            // default score over a real one for a field that was never touched.
            if (container.has(t.field().fullKey())) {
                index.updateScore(t.field().fullKey(), player.toString(), score(t, container));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> double score(TrackedLeaderboard<T> t, DataContainer container) {
        return t.scorer().applyAsDouble((T) container.get(t.field()));
    }

    /** Backfills the index for a tracked field by scanning existing stored players once. */
    public <T> void rebuildLeaderboard(PlayerField<T> field, ToDoubleFunction<T> scorer) {
        LeaderboardIndex index = leaderboardIndex();
        if (index == null) return;
        for (String id : storage.listIds("players")) {
            UUID player = UUID.fromString(id);
            index.updateScore(field.fullKey(), id, scorer.applyAsDouble(getFieldValue(player, field)));
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
            container.applyRemote(field, newValue);
        }
    }

    public List<String> listPlayerIds() {
        return storage.listIds("players");
    }
}
