package net.swofty;

import net.swofty.event.*;
import net.swofty.lock.DistributedLock;
import net.swofty.transaction.TransactionConsumer;
import net.swofty.transaction.TransactionFunction;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import net.swofty.storage.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

public interface DataAPI extends AutoCloseable {
    // Player fields
    <T> T get(UUID player, PlayerField<T> field);
    <T> CompletionStage<SaveResult> set(UUID player, PlayerField<T> field, T value);
    <T> CompletionStage<SaveResult> update(UUID player, PlayerField<T> field, UnaryOperator<T> updater);
    <T> CompletionStage<SaveResult> update(UUID player, PlayerField<T> field, UnaryOperator<T> updater, UpdateMode mode);

    // Linked fields (resolves via player's link key)
    <K, T> T get(UUID player, LinkedField<K, T> field);
    <K, T> CompletionStage<SaveResult> set(UUID player, LinkedField<K, T> field, T value);
    <K, T> CompletionStage<SaveResult> update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater);
    <K, T> CompletionStage<SaveResult> update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater, UpdateMode mode);

    // Direct linked access (when you have the key)
    <K, T> T getDirect(K key, LinkedField<K, T> field);
    <K, T> CompletionStage<SaveResult> setDirect(K key, LinkedField<K, T> field, T value);
    <K, T> CompletionStage<SaveResult> updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater);
    <K, T> CompletionStage<SaveResult> updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater, UpdateMode mode);

    // Link management
    <K> void link(UUID player, LinkType<K> type, K key);
    <K> void unlink(UUID player, LinkType<K> type);
    <K> Optional<K> getLinkKey(UUID player, LinkType<K> type);

    // Expiring fields
    <T> CompletionStage<SaveResult> set(UUID player, ExpiringField<T> field, T value);
    <T> CompletionStage<SaveResult> set(UUID player, ExpiringField<T> field, T value, Duration ttl);
    <T> Optional<Duration> getTimeRemaining(UUID player, ExpiringField<T> field);
    <T> boolean isExpired(UUID player, ExpiringField<T> field);
    <T> void extend(UUID player, ExpiringField<T> field, Duration additional);

    // Expiring linked fields
    <K, T> CompletionStage<SaveResult> set(UUID player, ExpiringLinkedField<K, T> field, T value);
    <K, T> CompletionStage<SaveResult> set(UUID player, ExpiringLinkedField<K, T> field, T value, Duration ttl);

    // Transactions
    <R> R transaction(UUID player, TransactionFunction<R> action);
    void transaction(UUID player, TransactionConsumer action);
    <K, R> R transactionDirect(K key, LinkType<K> type, TransactionFunction<R> action);

    // Events
    <T> void subscribe(PlayerField<T> field, PlayerDataListener<T> listener);
    <K, T> void subscribe(LinkedField<K, T> field, LinkedDataListener<K, T> listener);
    <K> void subscribe(LinkType<K> type, LinkChangeListener<K> listener);
    <T> void subscribeExpiration(ExpiringField<T> field, ExpirationListener<T> listener);
    <K, T> void subscribeExpiration(ExpiringLinkedField<K, T> field, LinkedExpirationListener<K, T> listener);

    // Bulk operations - Leaderboards
    <T extends Comparable<T>> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit);
    <T> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit, Comparator<T> comparator);
    <T extends Comparable<T>> Page<LeaderboardEntry<T>> getTopPaged(PlayerField<T> field, int page, int pageSize);

    // Bulk operations - Queries
    <T> List<UUID> query(PlayerField<T> field, Predicate<T> filter);
    <T> int count(PlayerField<T> field, Predicate<T> filter);

    // Bulk operations - Updates
    <T> int updateAll(PlayerField<T> field, UnaryOperator<T> updater);
    <T> int updateWhere(PlayerField<T> field, Predicate<T> filter, UnaryOperator<T> updater);

    // Bulk operations - Linked
    <K, T extends Comparable<T>> List<LeaderboardEntry<T>> getTopLinked(LinkedField<K, T> field, int limit);
    <K, T> List<K> queryLinked(LinkedField<K, T> field, Predicate<T> filter);

    // Leaderboard indexing - getTop/getTopPaged are index-backed and require a LeaderboardIndex-
    // capable storage (e.g. Redis sorted sets). No registration is needed for numeric fields: the
    // index self-builds on first rank and every node maintains it on write. trackLeaderboard only
    // registers a score function so a NON-numeric field can be ranked; rebuildLeaderboard forces a
    // rebuild from stored data.
    <T> void trackLeaderboard(PlayerField<T> field, ToDoubleFunction<T> scorer);
    <T> void rebuildLeaderboard(PlayerField<T> field);

    // Lifecycle - warm a player's data into this node before use, evict it when done.
    // This is the primitive a proxy uses to load a player's data on the target server
    // BEFORE moving them there, and to evict it afterwards so a later visit is never stale.
    void load(UUID player);
    CompletableFuture<Void> loadAsync(UUID player, Executor executor);
    default CompletableFuture<Void> loadAsync(UUID player) { return loadAsync(player, ForkJoinPool.commonPool()); }
    SaveResult flush(UUID player);
    CompletionStage<SaveResult> flushAsync(UUID player, Executor executor);
    default CompletionStage<SaveResult> flushAsync(UUID player) { return flushAsync(player, ForkJoinPool.commonPool()); }
    SaveResult unload(UUID player);
    CompletionStage<SaveResult> unloadAsync(UUID player, Executor executor);
    default CompletionStage<SaveResult> unloadAsync(UUID player) { return unloadAsync(player, ForkJoinPool.commonPool()); }
    boolean isLoaded(UUID player);
    Set<UUID> loadedPlayers();

    // Lifecycle - shared/linked entities (e.g. an island or coop shared across members)
    <K> void loadLink(LinkType<K> type, K key);
    <K> SaveResult flushLink(LinkType<K> type, K key);
    <K> CompletionStage<SaveResult> flushLinkAsync(LinkType<K> type, K key, Executor executor);
    <K> SaveResult unloadLink(LinkType<K> type, K key);
    <K> CompletionStage<SaveResult> unloadLinkAsync(LinkType<K> type, K key, Executor executor);
    <K> boolean isLinkLoaded(LinkType<K> type, K key);

    // Distributed locking - takes the configured DistributedLock for an app-level critical section
    // that spans more than one field or entity. Use with try-with-resources; requires a lock to
    // have been supplied to the implementation.
    DistributedLock.Handle lock(String key, Duration timeout);

    StorageSnapshot snapshot();
    CompletionStage<BatchSaveResult> saveSnapshot(StorageSnapshot snapshot);
    DataStorage storage();
    StorageOwnership storageOwnership();

    // Flushes deferred writes, stops expiration timers and closes pub/sub subscribers.
    void shutdown();

    @Override default void close() { shutdown(); }
}
