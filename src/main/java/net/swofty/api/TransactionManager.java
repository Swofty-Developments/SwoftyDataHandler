package net.swofty.api;

import net.swofty.DataField;
import net.swofty.LinkedField;
import net.swofty.PlayerField;
import net.swofty.LinkType;
import net.swofty.event.EventBus;
import net.swofty.lock.DistributedLock;
import net.swofty.transaction.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

class TransactionManager {
    private static final DistributedLock.Handle NO_OP = () -> {};

    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final LinkRegistryImpl linkRegistry;
    private final EventBus eventBus;
    private final DistributedLock distributedLock;
    private final Duration lockTimeout;
    // The API instance these transactions belong to, recorded with the lock so a write on a
    // different API sharing the same lock knows it still has to reread its own document.
    private final Object owner;

    public TransactionManager(PlayerDataManager playerData, LinkedDataManager linkedData, LinkRegistryImpl linkRegistry,
                              EventBus eventBus, DistributedLock distributedLock, Duration lockTimeout, Object owner) {
        this.playerData = playerData;
        this.linkedData = linkedData;
        this.linkRegistry = linkRegistry;
        this.eventBus = eventBus;
        this.distributedLock = distributedLock;
        this.lockTimeout = lockTimeout;
        this.owner = owner;
    }

    // Nothing is recorded without a distributed lock: there is no cross-node exclusion for a nested
    // write to inherit, and claiming otherwise let such a write skip an acquisition it needed.
    private void enterScope(String lockKey) {
        if (distributedLock != null) LockScope.enter(distributedLock, lockKey, owner);
    }

    private void exitScope(String lockKey) {
        if (distributedLock != null) LockScope.exit(distributedLock, lockKey, owner);
    }

    // A pending write, kept with the field it came from so commit can persist it and then fire
    // the same events a direct set() would.
    private record Write(DataField<?> field, Object value) {}

    // Cross-node mutual exclusion when a distributed lock is configured; a no-op handle
    // otherwise, leaving the JVM-local monitor as the only guard (single-node behaviour).
    private DistributedLock.Handle acquire(String key) {
        return distributedLock == null ? NO_OP : distributedLock.acquire(key, lockTimeout);
    }

    // The lock only serialises writers; it does not make this node's cached view fresh. A peer's
    // change may not have arrived yet, or may never arrive when no pub/sub is configured, and a
    // transaction reading that view would compute its new value from an already overwritten one.
    // Holding the exclusive lock, reread the entity so the body decodes what is really in storage.
    // With no distributed lock there is a single writer, whose own cache is already the truth.
    private void refreshPlayer(UUID player) {
        if (distributedLock == null) return;
        playerData.refresh(player);
    }

    private void refreshLinked(String linkTypeName, Object key) {
        if (distributedLock == null) return;
        linkedData.refresh(linkTypeName, key);
    }

    public <R> R execute(UUID player, TransactionFunction<R> action) {
        String lockKey = "player:" + player;
        // The lock key is recorded against this thread for the duration, so a write inside the body
        // that would transparently take the same non-reentrant lock can ride this one instead.
        try (DistributedLock.Handle handle = acquire(lockKey)) {
            synchronized (playerData.getLock(player)) {
                enterScope(lockKey);
                refreshPlayer(player);
                TransactionContext tx = new TransactionContext(player, null, null);
                try {
                    R result = action.apply(tx);
                    handle.ensureValid();
                    tx.commit();
                    return result;
                } catch (TransactionAbortException e) {
                    tx.rollback();
                    return null;
                } catch (Exception e) {
                    tx.rollback();
                    throw e;
                } finally {
                    exitScope(lockKey);
                }
            }
        }
    }

    public void execute(UUID player, TransactionConsumer action) {
        execute(player, tx -> {
            action.accept(tx);
            return null;
        });
    }

    public <K, R> R executeDirect(K key, LinkType<K> type, TransactionFunction<R> action) {
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        String lockKey = "linked:" + ck;
        try (DistributedLock.Handle handle = acquire(lockKey)) {
            synchronized (linkedData.getLock(ck)) {
                enterScope(lockKey);
                refreshLinked(type.name(), key);
                TransactionContext tx = new TransactionContext(null, type, key);
                try {
                    R result = action.apply(tx);
                    handle.ensureValid();
                    tx.commit();
                    return result;
                } catch (TransactionAbortException e) {
                    tx.rollback();
                    return null;
                } catch (Exception e) {
                    tx.rollback();
                    throw e;
                } finally {
                    exitScope(lockKey);
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
        private final Map<String, Write> newPlayerValues = new HashMap<>();
        private final Map<String, Object> originalLinkedValues = new HashMap<>();
        private final Map<String, Write> newLinkedValues = new HashMap<>();
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
            Write pending = newPlayerValues.get(field.fullKey());
            if (pending != null) {
                return (T) pending.value();
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
            newPlayerValues.put(field.fullKey(), new Write(field, value));
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
            Write pending = newLinkedValues.get(linkedKey);
            if (pending != null) {
                return (T) pending.value();
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
            newLinkedValues.put(linkedKey, new Write(field, value));
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

        @SuppressWarnings("unchecked")
        void commit() {
            if (committed || rolledBack) return;
            committed = true;

            // Apply player field changes. Every field of the transaction lands in one document
            // write, so they all carry that write's version; the bus orders per field, not per
            // document, which is what lets all of them through on the receiving node.
            long playerVersion = 0L;
            if (!newPlayerValues.isEmpty()) {
                DataContainer playerContainer = playerData.getContainer(player);
                for (Write write : newPlayerValues.values()) {
                    playerContainer.set((DataField<Object>) write.field(), write.value());
                }
                playerVersion = PlayerDataManager.eventVersion(playerData.persist(player));
            }

            // Apply linked field changes
            Map<String, Long> linkedVersions = new HashMap<>();
            for (Map.Entry<String, Write> entry : newLinkedValues.entrySet()) {
                Write write = entry.getValue();
                LinkedField<Object, Object> field = (LinkedField<Object, Object>) write.field();
                Object linkKey = resolveLink(field.linkType());
                if (linkKey != null) {
                    linkedVersions.put(entry.getKey(), PlayerDataManager.eventVersion(
                            linkedData.setFieldValue(field.linkType().name(), linkKey, field, write.value())));
                }
            }

            fireEvents(playerVersion, linkedVersions);
        }

        // A transactional write is still a write: without this, committed changes reached storage
        // but no listener ran locally, nothing was published, and peers kept serving stale caches.
        @SuppressWarnings("unchecked")
        private void fireEvents(long playerVersion, Map<String, Long> linkedVersions) {
            for (Map.Entry<String, Write> entry : newPlayerValues.entrySet()) {
                Write write = entry.getValue();
                eventBus.firePlayerDataChanged((DataField<Object>) write.field(), player,
                        originalPlayerValues.get(entry.getKey()), write.value(), playerVersion);
            }

            for (Map.Entry<String, Write> entry : newLinkedValues.entrySet()) {
                Write write = entry.getValue();
                LinkedField<Object, Object> field = (LinkedField<Object, Object>) write.field();
                Object linkKey = resolveLink(field.linkType());
                if (linkKey == null) continue;
                Set<UUID> affected = linkRegistry.getLinkedPlayers((LinkType<Object>) field.linkType(), linkKey);
                eventBus.fireLinkedDataChanged(field, linkKey,
                        originalLinkedValues.get(entry.getKey()), write.value(), affected,
                        linkedVersions.getOrDefault(entry.getKey(), 0L));
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
