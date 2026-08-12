package net.swofty.api;

import net.swofty.LinkedField;
import net.swofty.PlayerField;
import net.swofty.LinkType;
import net.swofty.lock.DistributedLock;
import net.swofty.transaction.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

class TransactionManager {
    private static final DistributedLock.Handle NO_OP = () -> {};

    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final LinkRegistryImpl linkRegistry;
    private final DistributedLock distributedLock;
    private final Duration lockTimeout;

    public TransactionManager(PlayerDataManager playerData, LinkedDataManager linkedData, LinkRegistryImpl linkRegistry) {
        this(playerData, linkedData, linkRegistry, null, Duration.ofSeconds(10));
    }

    public TransactionManager(PlayerDataManager playerData, LinkedDataManager linkedData, LinkRegistryImpl linkRegistry,
                              DistributedLock distributedLock, Duration lockTimeout) {
        this.playerData = playerData;
        this.linkedData = linkedData;
        this.linkRegistry = linkRegistry;
        this.distributedLock = distributedLock;
        this.lockTimeout = lockTimeout;
    }

    // Cross-node mutual exclusion when a distributed lock is configured; a no-op handle
    // otherwise, leaving the JVM-local monitor as the only guard (single-node behaviour).
    private DistributedLock.Handle acquire(String key) {
        return distributedLock == null ? NO_OP : distributedLock.acquire(key, lockTimeout);
    }

    public <R> R execute(UUID player, TransactionFunction<R> action) {
        try (DistributedLock.Handle ignored = acquire("player:" + player)) {
            synchronized (playerData.getLock(player)) {
                TransactionContext tx = new TransactionContext(player, null, null);
                try {
                    R result = action.apply(tx);
                    tx.commit();
                    return result;
                } catch (TransactionAbortException e) {
                    tx.rollback();
                    return null;
                } catch (Exception e) {
                    tx.rollback();
                    throw e;
                }
            }
        }
    }

    public void execute(UUID player, TransactionConsumer action) {
        try (DistributedLock.Handle ignored = acquire("player:" + player)) {
            synchronized (playerData.getLock(player)) {
                TransactionContext tx = new TransactionContext(player, null, null);
                try {
                    action.accept(tx);
                    tx.commit();
                } catch (TransactionAbortException e) {
                    tx.rollback();
                } catch (Exception e) {
                    tx.rollback();
                    throw e;
                }
            }
        }
    }

    public <K, R> R executeDirect(K key, LinkType<K> type, TransactionFunction<R> action) {
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        try (DistributedLock.Handle ignored = acquire("linked:" + ck)) {
            synchronized (linkedData.getLock(ck)) {
                TransactionContext tx = new TransactionContext(null, type, key);
                try {
                    R result = action.apply(tx);
                    tx.commit();
                    return result;
                } catch (TransactionAbortException e) {
                    tx.rollback();
                    return null;
                } catch (Exception e) {
                    tx.rollback();
                    throw e;
                }
            }
        }
    }

    private class TransactionContext implements Transaction {
        // Either a player transaction (player set) or a direct one bound to a single link key
        // (boundType/boundKey set), which is what lets a direct transaction operate without a player.
        private final UUID player;
        private final LinkType<?> boundType;
        private final Object boundKey;
        private final Map<String, Object> originalPlayerValues = new HashMap<>();
        private final Map<String, Object> newPlayerValues = new HashMap<>();
        private final Map<String, Object> originalLinkedValues = new HashMap<>();
        private final Map<String, Object> newLinkedValues = new HashMap<>();
        private boolean committed = false;
        private boolean rolledBack = false;

        TransactionContext(UUID player, LinkType<?> boundType, Object boundKey) {
            this.player = player;
            this.boundType = boundType;
            this.boundKey = boundKey;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(PlayerField<T> field) {
            requirePlayer();
            if (newPlayerValues.containsKey(field.fullKey())) {
                return (T) newPlayerValues.get(field.fullKey());
            }
            T value = playerData.getFieldValue(player, field);
            originalPlayerValues.putIfAbsent(field.fullKey(), value);
            return value;
        }

        @Override
        public <T> void set(PlayerField<T> field, T value) {
            requirePlayer();
            Validation.validate(field, value);
            if (!originalPlayerValues.containsKey(field.fullKey())) {
                originalPlayerValues.put(field.fullKey(), playerData.getFieldValue(player, field));
            }
            newPlayerValues.put(field.fullKey(), value);
        }

        @Override
        public <T> void update(PlayerField<T> field, UnaryOperator<T> updater) {
            T current = get(field);
            set(field, updater.apply(current));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, T> T get(LinkedField<K, T> field) {
            String linkedKey = linkedKey(field);
            if (newLinkedValues.containsKey(linkedKey)) {
                return (T) newLinkedValues.get(linkedKey);
            }
            K linkKey = resolveLink(field.linkType());
            if (linkKey == null) return field.defaultValue();
            T value = linkedData.getFieldValue(field.linkType().name(), linkKey, field);
            originalLinkedValues.putIfAbsent(linkedKey, value);
            return value;
        }

        @Override
        public <K, T> void set(LinkedField<K, T> field, T value) {
            Validation.validate(field, value);
            String linkedKey = linkedKey(field);
            K linkKey = resolveLink(field.linkType());
            if (linkKey == null) {
                throw new IllegalStateException(unresolvedLink(field.linkType()));
            }
            if (!originalLinkedValues.containsKey(linkedKey)) {
                originalLinkedValues.put(linkedKey, linkedData.getFieldValue(field.linkType().name(), linkKey, field));
            }
            newLinkedValues.put(linkedKey, value);
        }

        @Override
        public <K, T> void update(LinkedField<K, T> field, UnaryOperator<T> updater) {
            T current = get(field);
            set(field, updater.apply(current));
        }

        @Override
        public void abort() {
            throw new TransactionAbortException();
        }

        private String linkedKey(LinkedField<?, ?> field) {
            return field.linkType().name() + ":" + field.fullKey();
        }

        // A direct transaction already knows its key; a player transaction looks it up.
        @SuppressWarnings("unchecked")
        private <K> K resolveLink(LinkType<K> type) {
            if (boundType != null) {
                return boundType.name().equals(type.name()) ? (K) boundKey : null;
            }
            return player == null ? null : linkRegistry.resolve(player, type);
        }

        private Object resolveLink(String linkTypeName) {
            if (boundType != null) {
                return boundType.name().equals(linkTypeName) ? boundKey : null;
            }
            return player == null ? null : linkRegistry.resolve(player, linkTypeName);
        }

        private void requirePlayer() {
            if (player == null) {
                throw new IllegalStateException("Player fields are not available inside a direct transaction;"
                        + " use transaction(player, ...) for player data");
            }
        }

        private String unresolvedLink(LinkType<?> type) {
            if (boundType != null) {
                return "Transaction is bound to link type " + boundType.name() + ", not " + type.name();
            }
            return "Player " + player + " is not linked to " + type.name();
        }

        void commit() {
            if (committed || rolledBack) return;
            committed = true;

            // Apply player field changes
            if (!newPlayerValues.isEmpty()) {
                DataContainer playerContainer = playerData.getContainer(player);
                for (Map.Entry<String, Object> entry : newPlayerValues.entrySet()) {
                    playerContainer.rawData().put(entry.getKey(), entry.getValue());
                }
                playerData.persist(player);
            }

            // Apply linked field changes
            for (Map.Entry<String, Object> entry : newLinkedValues.entrySet()) {
                String lk = entry.getKey();
                int colonIdx = lk.indexOf(':');
                String linkTypeName = lk.substring(0, colonIdx);
                String fieldFullKey = lk.substring(colonIdx + 1);

                Object linkKey = resolveLink(linkTypeName);
                if (linkKey != null) {
                    linkedData.setFieldValue(linkTypeName, linkKey,
                            new SimpleFieldRef(fieldFullKey), entry.getValue());
                }
            }
        }

        void rollback() {
            if (committed || rolledBack) return;
            rolledBack = true;

            if (player != null) {
                DataContainer playerContainer = playerData.getContainer(player);
                for (Map.Entry<String, Object> entry : originalPlayerValues.entrySet()) {
                    restore(playerContainer, entry.getKey(), entry.getValue());
                }
            }

            for (Map.Entry<String, Object> entry : originalLinkedValues.entrySet()) {
                String lk = entry.getKey();
                int colonIdx = lk.indexOf(':');
                String linkTypeName = lk.substring(0, colonIdx);
                String fieldFullKey = lk.substring(colonIdx + 1);

                Object linkKey = resolveLink(linkTypeName);
                if (linkKey == null) continue;
                DataContainer container = linkedData.getContainer(
                        LinkedDataManager.compositeKey(linkTypeName, linkKey));
                restore(container, fieldFullKey, entry.getValue());
            }
        }

        private void restore(DataContainer container, String fullKey, Object value) {
            if (value == null) {
                container.rawData().remove(fullKey);
            } else {
                container.rawData().put(fullKey, value);
            }
        }
    }
}
