package net.swofty.api;

import net.swofty.*;
import net.swofty.data.DataFormat;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.*;
import net.swofty.lock.DistributedLock;
import net.swofty.storage.DataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.StorageOwnership;
import net.swofty.transaction.TransactionConsumer;
import net.swofty.transaction.TransactionFunction;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

public class DataAPIImpl implements DataAPI {
    /** How long a transaction or a distributed update waits for the entity's lock by default. */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final DataStorage storage;
    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final LinkRegistryImpl linkRegistry;
    private final ExpirationManager expirationManager;
    private final TransactionManager transactionManager;
    private final EventBus eventBus;
    private final BulkOperationExecutor bulkOperations;
    private final DistributedLock distributedLock;
    private final StorageOwnership storageOwnership;
    private final Duration lockTimeout;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public DataAPIImpl(DataStorage storage, DataFormat format, PubSubHandler pubSub) {
        this(storage, format, pubSub, true);
    }

    public DataAPIImpl(DataStorage storage, DataFormat format, PubSubHandler pubSub, boolean autoPersist) {
        this(storage, format, pubSub, autoPersist, null);
    }

    /**
     * @param autoPersist     when true (the default) every write is flushed to storage immediately;
     *                        when false writes stay in the cache until {@link #flush(UUID)} or
     *                        {@link #unload(UUID)}, letting a node batch a play session into one write.
     * @param distributedLock when non-null, transactions and {@link #lock(String, Duration)} use it for
     *                        cross-node mutual exclusion; when null they fall back to a JVM-local lock.
     */
    public DataAPIImpl(DataStorage storage, DataFormat format, PubSubHandler pubSub, boolean autoPersist,
                       DistributedLock distributedLock) {
        this(storage, format, pubSub, autoPersist, distributedLock, StorageOwnership.BORROWED, DEFAULT_LOCK_TIMEOUT);
    }

    /**
     * @param ownership   OWNED makes {@link #shutdown()} close the storage (and the distributed lock,
     *                    when it is closeable) as well; BORROWED, the default, leaves both alone
     *                    because the application may still be using them.
     * @param lockTimeout how long transactions and {@link UpdateMode#DISTRIBUTED} writes wait for an
     *                    entity's distributed lock before giving up.
     */
    public DataAPIImpl(DataStorage storage, DataFormat format, PubSubHandler pubSub, boolean autoPersist,
                       DistributedLock distributedLock, StorageOwnership ownership, Duration lockTimeout) {
        this.storage = storage;
        this.storageOwnership = Objects.requireNonNull(ownership, "ownership");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        this.distributedLock = distributedLock;
        this.eventBus = (pubSub != null) ? new DistributedEventBus(pubSub) : new EventBus();
        this.linkRegistry = new LinkRegistryImpl();
        this.playerData = new PlayerDataManager(storage, format, eventBus, autoPersist);
        // Links live in shared storage on the player's own document, so a node that never linked
        // the player itself can still recover the key instead of behaving as if they had no link.
        this.linkRegistry.setKeyLoader(playerData::loadLinkKey);
        this.linkedData = new LinkedDataManager(storage, format, eventBus, linkRegistry, autoPersist);
        this.expirationManager = new ExpirationManager(eventBus);
        this.transactionManager = new TransactionManager(playerData, linkedData, linkRegistry, eventBus,
                distributedLock, lockTimeout);
        this.bulkOperations = new BulkOperationExecutor(playerData, linkedData, storage, eventBus);

        // Keep locally cached containers coherent with changes made on other nodes.
        if (eventBus instanceof DistributedEventBus distributed) {
            distributed.setRemoteChangeHandler(new RemoteChangeHandler() {
                @Override
                public <T> void onPlayerChange(DataField<T> field, UUID player, T newValue) {
                    playerData.applyRemote(field, player, newValue);
                }

                @Override
                public <T> void onLinkedChange(DataField<T> field, String linkTypeName, String linkKey, T newValue) {
                    linkedData.applyRemote(linkTypeName, linkKey, field, newValue);
                }

                @Override
                public <K> void onLinked(LinkType<K> type, UUID player, K linkKey) {
                    linkRegistry.link(player, type, linkKey);
                    playerData.applyRemote(type.playerField(), player, linkKey);
                }

                @Override
                public <K> void onUnlinked(LinkType<K> type, UUID player, K previousKey) {
                    linkRegistry.unlink(player, type);
                    playerData.applyRemote(type.playerField(), player, null);
                }

                @Override
                public void onPlayerSnapshot(UUID player, long version) {
                    // A snapshot replaces the whole document, links included, so the registry has to
                    // be re-derived from it or this node keeps resolving a link the document dropped.
                    if (playerData.applyRemoteSnapshot(player, version)) {
                        linkRegistry.reconcile(player);
                    }
                }

                @Override
                public void onLinkedSnapshot(String linkTypeName, String linkKey, long version) {
                    linkedData.applyRemoteSnapshot(linkTypeName, linkKey, version);
                }

                @Override
                public <K> Set<UUID> onLinkedDeleted(LinkType<K> type, K linkKey) {
                    Set<UUID> affected = new HashSet<>(linkRegistry.getLinkedPlayers(type, linkKey));
                    for (UUID player : affected) {
                        linkRegistry.unlink(player, type);
                        playerData.applyRemote(type.playerField(), player, null);
                    }
                    expirationManager.clearLinked(type.name(), linkKey);
                    linkedData.evict(type.name(), linkKey);
                    return affected;
                }
            });
        }
    }

    public DataAPIImpl(DataStorage storage, DataFormat format) {
        this(storage, format, null);
    }

    public DataAPIImpl(DataStorage storage) {
        this(storage, new JsonFormat(), null);
    }

    public DataAPIImpl(DataStorage storage, PubSubHandler pubSub) {
        this(storage, new JsonFormat(), pubSub);
    }

    public DataAPIImpl(DataStorage storage, StorageOwnership ownership) {
        this(storage, new JsonFormat(), null, true, null, ownership, DEFAULT_LOCK_TIMEOUT);
    }

    // ==================== Player Fields ====================

    @Override
    public <T> T get(UUID player, PlayerField<T> field) {
        return playerData.get(player, field, expirationManager);
    }

    @Override
    public <T> void set(UUID player, PlayerField<T> field, T value) {
        playerData.set(player, field, value);
    }

    @Override
    public <T> void update(UUID player, PlayerField<T> field, UnaryOperator<T> updater) {
        playerData.update(player, field, updater);
    }

    @Override
    public <T> void update(UUID player, PlayerField<T> field, UnaryOperator<T> updater, UpdateMode mode) {
        if (mode != UpdateMode.DISTRIBUTED) {
            playerData.update(player, field, updater);
            return;
        }
        underEntityLock("player:" + player, playerData.getLock(player), () -> playerData.refresh(player),
                () -> playerData.update(player, field, updater));
    }

    // ==================== Linked Fields ====================

    @Override
    public <K, T> T get(UUID player, LinkedField<K, T> field) {
        return linkedData.get(player, field);
    }

    @Override
    public <K, T> void set(UUID player, LinkedField<K, T> field, T value) {
        linkedData.set(player, field, value);
    }

    @Override
    public <K, T> void update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater) {
        linkedData.update(player, field, updater);
    }

    @Override
    public <K, T> void update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater, UpdateMode mode) {
        K key = linkRegistry.resolve(player, field.linkType());
        if (key == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        updateDirect(key, field, updater, mode);
    }

    @Override
    public <K, T> T getDirect(K key, LinkedField<K, T> field) {
        return linkedData.getDirect(key, field, expirationManager);
    }

    @Override
    public <K, T> void setDirect(K key, LinkedField<K, T> field, T value) {
        linkedData.setDirect(key, field, value);
    }

    @Override
    public <K, T> void updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater) {
        linkedData.updateDirect(key, field, updater);
    }

    @Override
    public <K, T> void updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater, UpdateMode mode) {
        if (mode != UpdateMode.DISTRIBUTED) {
            linkedData.updateDirect(key, field, updater);
            return;
        }
        String ck = LinkedDataManager.compositeKey(field.linkType().name(), key);
        underEntityLock("linked:" + ck, linkedData.getLock(ck),
                () -> linkedData.refresh(field.linkType().name(), key),
                () -> linkedData.updateDirect(key, field, updater));
    }

    /**
     * Runs a read-modify-write under the entity's cross-node lock.
     *
     * <p>Already inside that lock (a transaction on the same entity, or a nested distributed
     * update) the lock is not taken again: it is not reentrant, so re-acquiring it would block
     * until the acquisition timed out. Wanting a *different* entity's lock while holding one is
     * refused outright rather than attempted, because two nodes doing that in opposite orders
     * deadlock each other until both leases expire; take {@link #lock(String, Duration)} explicitly
     * in a fixed order instead.
     */
    private void underEntityLock(String lockKey, Object monitor, Runnable refresh, Supplier<SaveResult> write) {
        requireDistributedLock();
        if (LockScope.holds(lockKey)) {
            synchronized (monitor) {
                write.get();
            }
            return;
        }
        if (LockScope.holdsAnything()) {
            throw new IllegalStateException("Cannot take the distributed lock for " + lockKey
                    + " while this thread holds " + LockScope.describeHeld()
                    + "; use lock(key, timeout) explicitly if you need more than one entity");
        }
        try (DistributedLock.Handle handle = distributedLock.acquire(lockKey, lockTimeout)) {
            synchronized (monitor) {
                LockScope.enter(lockKey);
                try {
                    refresh.run();
                    write.get();
                    handle.ensureValid();
                } finally {
                    LockScope.exit(lockKey);
                }
            }
        }
    }

    // ==================== Link Management ====================

    @Override
    public <K> void link(UUID player, LinkType<K> type, K key) {
        synchronized (playerData.getLock(player)) {
            linkRegistry.link(player, type, key);
            SaveResult saved = playerData.setFieldValue(player, type.playerField(), key);
            eventBus.fireLinked(type, player, key, PlayerDataManager.eventVersion(saved));
        }
    }

    @Override
    public <K> void unlink(UUID player, LinkType<K> type) {
        synchronized (playerData.getLock(player)) {
            K previousKey = linkRegistry.unlink(player, type);
            if (previousKey == null) return;
            SaveResult saved = playerData.setFieldValue(player, type.playerField(), null);
            eventBus.fireUnlinked(type, player, previousKey, PlayerDataManager.eventVersion(saved));
        }
    }

    @Override
    public <K> Optional<K> getLinkKey(UUID player, LinkType<K> type) {
        return linkRegistry.getLinkKey(player, type);
    }

    // ==================== Expiring Fields ====================

    @Override
    public <T> void set(UUID player, ExpiringField<T> field, T value) {
        set(player, field, value, field.defaultTtl());
    }

    @Override
    public <T> void set(UUID player, ExpiringField<T> field, T value, Duration ttl) {
        Validation.validate(field, value);
        synchronized (playerData.getLock(player)) {
            T oldValue = playerData.getFieldValue(player, field);
            SaveResult saved = playerData.setFieldValue(player, field, value);
            expirationManager.setExpiration(player, field, ttl, value);
            eventBus.firePlayerDataChanged(field, player, oldValue, value, PlayerDataManager.eventVersion(saved));
        }
    }

    @Override
    public <T> Optional<Duration> getTimeRemaining(UUID player, ExpiringField<T> field) {
        return expirationManager.getTimeRemaining(player, field);
    }

    @Override
    public <T> boolean isExpired(UUID player, ExpiringField<T> field) {
        return expirationManager.isExpired(player, field);
    }

    @Override
    public <T> void extend(UUID player, ExpiringField<T> field, Duration additional) {
        expirationManager.extend(player, field, additional);
    }

    @Override
    public <K, T> void set(UUID player, ExpiringLinkedField<K, T> field, T value) {
        set(player, field, value, field.defaultTtl());
    }

    @Override
    public <K, T> void set(UUID player, ExpiringLinkedField<K, T> field, T value, Duration ttl) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        Validation.validate(field, value);
        String ck = LinkedDataManager.compositeKey(field.linkType().name(), linkKey);
        synchronized (linkedData.getLock(ck)) {
            T oldValue = linkedData.getFieldValue(field.linkType().name(), linkKey, field);
            SaveResult saved = linkedData.setFieldValue(field.linkType().name(), linkKey, field, value);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), linkKey);
            expirationManager.setLinkedExpiration(field, linkKey, ttl, value, affected);
            eventBus.fireLinkedDataChanged(field, linkKey, oldValue, value, affected,
                    PlayerDataManager.eventVersion(saved));
        }
    }

    // ==================== Transactions ====================

    @Override
    public <R> R transaction(UUID player, TransactionFunction<R> action) {
        return transactionManager.execute(player, action);
    }

    @Override
    public void transaction(UUID player, TransactionConsumer action) {
        transactionManager.execute(player, action);
    }

    @Override
    public <K, R> R transactionDirect(K key, LinkType<K> type, TransactionFunction<R> action) {
        return transactionManager.executeDirect(key, type, action);
    }

    // ==================== Events ====================

    @Override
    public <T> void subscribe(PlayerField<T> field, PlayerDataListener<T> listener) {
        eventBus.subscribe(field, listener);
    }

    @Override
    public <K, T> void subscribe(LinkedField<K, T> field, LinkedDataListener<K, T> listener) {
        eventBus.subscribeLinked(field, listener);
    }

    @Override
    public <K> void subscribe(LinkType<K> type, LinkChangeListener<K> listener) {
        eventBus.subscribeLinkChange(type, listener);
    }

    @Override
    public <T> void subscribeExpiration(ExpiringField<T> field, ExpirationListener<T> listener) {
        eventBus.subscribeExpiration(field, listener);
    }

    @Override
    public <K, T> void subscribeExpiration(ExpiringLinkedField<K, T> field, LinkedExpirationListener<K, T> listener) {
        eventBus.subscribeLinkedExpiration(field, listener);
    }

    // ==================== Bulk Operations ====================

    @Override
    public <T extends Comparable<T>> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit) {
        return bulkOperations.getTop(field, limit);
    }

    @Override
    public <T> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit, Comparator<T> comparator) {
        return bulkOperations.getTop(field, limit, comparator);
    }

    @Override
    public <T extends Comparable<T>> Page<LeaderboardEntry<T>> getTopPaged(PlayerField<T> field, int page, int pageSize) {
        return bulkOperations.getTopPaged(field, page, pageSize);
    }

    @Override
    public <T> List<UUID> query(PlayerField<T> field, Predicate<T> filter) {
        return bulkOperations.query(field, filter);
    }

    @Override
    public <T> int count(PlayerField<T> field, Predicate<T> filter) {
        return bulkOperations.count(field, filter);
    }

    @Override
    public <T> int updateAll(PlayerField<T> field, UnaryOperator<T> updater) {
        return bulkOperations.updateAll(field, updater);
    }

    @Override
    public <T> int updateWhere(PlayerField<T> field, Predicate<T> filter, UnaryOperator<T> updater) {
        return bulkOperations.updateWhere(field, filter, updater);
    }

    @Override
    public <K, T extends Comparable<T>> List<LeaderboardEntry<T>> getTopLinked(LinkedField<K, T> field, int limit) {
        return bulkOperations.getTopLinked(field, limit);
    }

    @Override
    public <K, T> List<K> queryLinked(LinkedField<K, T> field, Predicate<T> filter) {
        return bulkOperations.queryLinked(field, filter);
    }

    @Override
    public <T> void trackLeaderboard(PlayerField<T> field, ToDoubleFunction<T> scorer) {
        playerData.trackLeaderboard(field, scorer);
    }

    @Override
    public <T> void rebuildLeaderboard(PlayerField<T> field) {
        playerData.rebuildLeaderboard(field);
    }

    // ==================== Lifecycle ====================

    @Override
    public void load(UUID player) {
        playerData.load(player);
    }

    @Override
    public CompletableFuture<Void> loadAsync(UUID player, Executor executor) {
        return playerData.loadAsync(player, executor);
    }

    @Override
    public void flush(UUID player) {
        playerData.flush(player);
    }

    @Override
    public CompletableFuture<Void> flushAsync(UUID player, Executor executor) {
        return playerData.flushAsync(player, executor);
    }

    @Override
    public void unload(UUID player) {
        playerData.unload(player);
    }

    @Override
    public CompletableFuture<Void> unloadAsync(UUID player, Executor executor) {
        return playerData.unloadAsync(player, executor);
    }

    @Override
    public boolean isLoaded(UUID player) {
        return playerData.isLoaded(player);
    }

    @Override
    public Set<UUID> loadedPlayers() {
        return playerData.loadedPlayers();
    }

    @Override
    public <K> void loadLink(LinkType<K> type, K key) {
        linkedData.loadLinked(type.name(), key);
    }

    @Override
    public <K> void flushLink(LinkType<K> type, K key) {
        linkedData.flushLinked(type.name(), key);
    }

    @Override
    public <K> void unloadLink(LinkType<K> type, K key) {
        linkedData.unloadLinked(type.name(), key);
    }

    @Override
    public <K> void deleteLink(LinkType<K> type, K key) {
        // Unlink the members first: each of those is a write to a player document that has to
        // happen whether or not the shared document deletion below succeeds.
        for (UUID player : new HashSet<>(linkRegistry.getLinkedPlayers(type, key))) {
            unlink(player, type);
        }
        expirationManager.clearLinked(type.name(), key);
        linkedData.deleteLinked(type.name(), key);
        eventBus.fireLinkDeleted(type, key);
    }

    @Override
    public <K> boolean isLinkLoaded(LinkType<K> type, K key) {
        return linkedData.isLinkedLoaded(type.name(), key);
    }

    /**
     * Acquires the configured {@link DistributedLock} for an arbitrary key, for app-level critical
     * sections that span more than one field or entity (e.g. transferring coins between two coops).
     * Use with try-with-resources. Requires a distributed lock to have been supplied.
     */
    @Override
    public DistributedLock.Handle lock(String key, Duration timeout) {
        requireDistributedLock();
        return distributedLock.acquire(key, timeout);
    }

    private void requireDistributedLock() {
        if (distributedLock == null) {
            throw new IllegalStateException("No DistributedLock configured on this DataAPI");
        }
    }

    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        // Every stage runs even if an earlier one fails, so one broken subsystem cannot leak the
        // threads and connections held by the rest.
        RuntimeException failure = null;
        try { playerData.flushAll(); } catch (RuntimeException e) { failure = e; }
        try { linkedData.flushAll(); } catch (RuntimeException e) { failure = suppress(failure, e); }
        try { expirationManager.shutdown(); } catch (RuntimeException e) { failure = suppress(failure, e); }
        if (eventBus instanceof DistributedEventBus deb) {
            try { deb.shutdown(); } catch (RuntimeException e) { failure = suppress(failure, e); }
        }
        if (storageOwnership == StorageOwnership.OWNED) {
            try { storage.close(); } catch (Exception e) { failure = suppress(failure, asRuntime(e)); }
            if (distributedLock instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception e) { failure = suppress(failure, asRuntime(e)); }
            }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException suppress(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static RuntimeException asRuntime(Exception e) {
        return e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
    }
}
