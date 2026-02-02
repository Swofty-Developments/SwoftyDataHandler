package net.swofty;

import net.swofty.event.*;
import net.swofty.transaction.TransactionConsumer;
import net.swofty.transaction.TransactionFunction;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public interface DataAPI {
    // Player fields
    <T> T get(UUID player, PlayerField<T> field);
    <T> void set(UUID player, PlayerField<T> field, T value);
    <T> void update(UUID player, PlayerField<T> field, UnaryOperator<T> updater);

    // Linked fields (resolves via player's link key)
    <K, T> T get(UUID player, LinkedField<K, T> field);
    <K, T> void set(UUID player, LinkedField<K, T> field, T value);
    <K, T> void update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater);

    // Direct linked access (when you have the key)
    <K, T> T getDirect(K key, LinkedField<K, T> field);
    <K, T> void setDirect(K key, LinkedField<K, T> field, T value);
    <K, T> void updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater);

    // Link management
    <K> void link(UUID player, LinkType<K> type, K key);
    <K> void unlink(UUID player, LinkType<K> type);
    <K> Optional<K> getLinkKey(UUID player, LinkType<K> type);

    // Expiring fields
    <T> void set(UUID player, ExpiringField<T> field, T value);
    <T> void set(UUID player, ExpiringField<T> field, T value, Duration ttl);
    <T> Optional<Duration> getTimeRemaining(UUID player, ExpiringField<T> field);
    <T> boolean isExpired(UUID player, ExpiringField<T> field);
    <T> void extend(UUID player, ExpiringField<T> field, Duration additional);

    // Expiring linked fields
    <K, T> void set(UUID player, ExpiringLinkedField<K, T> field, T value);
    <K, T> void set(UUID player, ExpiringLinkedField<K, T> field, T value, Duration ttl);

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
}
