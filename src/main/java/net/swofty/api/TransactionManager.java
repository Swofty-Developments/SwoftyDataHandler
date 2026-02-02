package net.swofty.api;

import net.swofty.LinkedField;
import net.swofty.PlayerField;
import net.swofty.LinkType;
import net.swofty.transaction.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class TransactionManager {
    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final LinkRegistryImpl linkRegistry;

    public TransactionManager(PlayerDataManager playerData, LinkedDataManager linkedData, LinkRegistryImpl linkRegistry) {
        this.playerData = playerData;
        this.linkedData = linkedData;
        this.linkRegistry = linkRegistry;
    }

    public <R> R execute(UUID player, TransactionFunction<R> action) {
        synchronized (playerData.getLock(player)) {
            TransactionContext tx = new TransactionContext(player);
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

    public void execute(UUID player, TransactionConsumer action) {
        synchronized (playerData.getLock(player)) {
            TransactionContext tx = new TransactionContext(player);
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

    public <K, R> R executeDirect(K key, LinkType<K> type, TransactionFunction<R> action) {
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        synchronized (linkedData.getLock(ck)) {
            TransactionContext tx = new TransactionContext(null);
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

    private class TransactionContext implements Transaction {
        private final UUID player;
        private final Map<String, Object> originalPlayerValues = new HashMap<>();
        private final Map<String, Object> newPlayerValues = new HashMap<>();
        private final Map<String, Object> originalLinkedValues = new HashMap<>();
        private final Map<String, Object> newLinkedValues = new HashMap<>();
        private boolean committed = false;
        private boolean rolledBack = false;

        TransactionContext(UUID player) {
            this.player = player;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(PlayerField<T> field) {
            if (newPlayerValues.containsKey(field.fullKey())) {
                return (T) newPlayerValues.get(field.fullKey());
            }
            T value = playerData.getFieldValue(player, field);
            originalPlayerValues.putIfAbsent(field.fullKey(), value);
            return value;
        }

        @Override
        public <T> void set(PlayerField<T> field, T value) {
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
            String linkedKey = field.linkType().name() + ":" + field.fullKey();
            if (newLinkedValues.containsKey(linkedKey)) {
                return (T) newLinkedValues.get(linkedKey);
            }
            K linkKey = linkRegistry.resolve(player, field.linkType());
            if (linkKey == null) return field.defaultValue();
            T value = linkedData.getFieldValue(field.linkType().name(), linkKey, field);
            originalLinkedValues.putIfAbsent(linkedKey, value);
            return value;
        }

        @Override
        public <K, T> void set(LinkedField<K, T> field, T value) {
            Validation.validate(field, value);
            String linkedKey = field.linkType().name() + ":" + field.fullKey();
            if (!originalLinkedValues.containsKey(linkedKey)) {
                K linkKey = linkRegistry.resolve(player, field.linkType());
                if (linkKey != null) {
                    originalLinkedValues.put(linkedKey, linkedData.getFieldValue(field.linkType().name(), linkKey, field));
                }
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

        void commit() {
            if (committed || rolledBack) return;
            committed = true;

            // Apply player field changes
            DataContainer playerContainer = playerData.getContainer(player);
            for (Map.Entry<String, Object> entry : newPlayerValues.entrySet()) {
                playerContainer.rawData().put(entry.getKey(), entry.getValue());
            }
            if (!newPlayerValues.isEmpty()) {
                playerData.persist(player);
            }

            // Apply linked field changes
            for (Map.Entry<String, Object> entry : newLinkedValues.entrySet()) {
                String lk = entry.getKey();
                int colonIdx = lk.indexOf(':');
                String linkTypeName = lk.substring(0, colonIdx);
                String fieldFullKey = lk.substring(colonIdx + 1);
                Object newValue = entry.getValue();

                Object linkKey = linkRegistry.resolve(player, linkTypeName);
                if (linkKey != null) {
                    String ck = LinkedDataManager.compositeKey(linkTypeName, linkKey);
                    // Use setFieldValue to persist
                    linkedData.setFieldValue(linkTypeName, linkKey,
                            new SimpleFieldRef(fieldFullKey), newValue);
                }
            }
        }

        void rollback() {
            if (committed || rolledBack) return;
            rolledBack = true;
            DataContainer playerContainer = playerData.getContainer(player);
            for (Map.Entry<String, Object> entry : originalPlayerValues.entrySet()) {
                if (entry.getValue() == null) {
                    playerContainer.rawData().remove(entry.getKey());
                } else {
                    playerContainer.rawData().put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
