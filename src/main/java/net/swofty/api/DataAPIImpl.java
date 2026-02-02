package net.swofty.api;

import net.swofty.*;
import net.swofty.data.DataFormat;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.*;
import net.swofty.storage.DataStorage;
import net.swofty.storage.RedisDataStorage;
import net.swofty.transaction.TransactionConsumer;
import net.swofty.transaction.TransactionFunction;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class DataAPIImpl implements DataAPI {
    private final DataStorage storage;
    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final LinkRegistryImpl linkRegistry;
    private final ExpirationManager expirationManager;
    private final TransactionManager transactionManager;
    private final EventBus eventBus;
    private final BulkOperationExecutor bulkOperations;

    public DataAPIImpl(DataStorage storage, DataFormat format) {
        this.storage = storage;
        this.eventBus = createEventBus(storage);
        this.linkRegistry = new LinkRegistryImpl();
        this.playerData = new PlayerDataManager(storage, format, eventBus);
        this.linkedData = new LinkedDataManager(storage, format, eventBus, linkRegistry);
        this.expirationManager = new ExpirationManager();
        this.transactionManager = new TransactionManager(playerData, linkedData, linkRegistry);
        this.bulkOperations = new BulkOperationExecutor(playerData, linkedData, storage, eventBus);
    }

    public DataAPIImpl(DataStorage storage) {
        this(storage, new JsonFormat());
    }

    private static EventBus createEventBus(DataStorage storage) {
        if (storage instanceof RedisDataStorage redisStorage) {
            return new DistributedEventBus(redisStorage.getPool());
        }
        return new EventBus();
    }

    private void requireListenerSupport() {
        if (!storage.supportsListeners()) {
            throw new UnsupportedOperationException(
                    "Event listeners are not supported with " + storage.getClass().getSimpleName()
                            + ". Use a storage backend that supports listeners (e.g. RedisDataStorage).");
        }
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

    // ==================== Link Management ====================

    @Override
    public <K> void link(UUID player, LinkType<K> type, K key) {
        synchronized (playerData.getLock(player)) {
            linkRegistry.link(player, type, key);
            playerData.setFieldValue(player, type.playerField(), key);
            eventBus.fireLinked(type, player, key);
        }
    }

    @Override
    public <K> void unlink(UUID player, LinkType<K> type) {
        synchronized (playerData.getLock(player)) {
            K previousKey = linkRegistry.unlink(player, type);
            if (previousKey == null) return;
            playerData.setFieldValue(player, type.playerField(), null);
            eventBus.fireUnlinked(type, player, previousKey);
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
            playerData.setFieldValue(player, field, value);
            expirationManager.setExpiration(player, field, ttl);
            eventBus.firePlayerDataChanged(field, player, oldValue, value);
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
            linkedData.setFieldValue(field.linkType().name(), linkKey, field, value);
            expirationManager.setLinkedExpiration(field.linkType().name(), linkKey, field, ttl);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), linkKey);
            eventBus.fireLinkedDataChanged(field, linkKey, oldValue, value, affected);
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
        requireListenerSupport();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.registerField(field);
        }
        eventBus.subscribe(field, listener);
    }

    @Override
    public <K, T> void subscribe(LinkedField<K, T> field, LinkedDataListener<K, T> listener) {
        requireListenerSupport();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.registerField(field);
        }
        eventBus.subscribeLinked(field, listener);
    }

    @Override
    public <K> void subscribe(LinkType<K> type, LinkChangeListener<K> listener) {
        requireListenerSupport();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.registerLinkType(type);
        }
        eventBus.subscribeLinkChange(type, listener);
    }

    @Override
    public <T> void subscribeExpiration(ExpiringField<T> field, ExpirationListener<T> listener) {
        requireListenerSupport();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.registerField(field);
        }
        eventBus.subscribeExpiration(field, listener);
    }

    @Override
    public <K, T> void subscribeExpiration(ExpiringLinkedField<K, T> field, LinkedExpirationListener<K, T> listener) {
        requireListenerSupport();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.registerField(field);
        }
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

    // ==================== Lifecycle ====================

    public void shutdown() {
        expirationManager.shutdown();
        if (eventBus instanceof DistributedEventBus deb) {
            deb.shutdown();
        }
    }
}
