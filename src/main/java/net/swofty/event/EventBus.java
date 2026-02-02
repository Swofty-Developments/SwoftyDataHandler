package net.swofty.event;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.ExpiringLinkedField;
import net.swofty.LinkType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private final Map<String, List<PlayerDataListener<?>>> playerListeners = new ConcurrentHashMap<>();
    private final Map<String, List<LinkedDataListener<?, ?>>> linkedListeners = new ConcurrentHashMap<>();
    private final Map<String, List<LinkChangeListener<?>>> linkChangeListeners = new ConcurrentHashMap<>();
    private final Map<String, List<ExpirationListener<?>>> expirationListeners = new ConcurrentHashMap<>();
    private final Map<String, List<LinkedExpirationListener<?, ?>>> linkedExpirationListeners = new ConcurrentHashMap<>();

    public <T> void subscribe(DataField<T> field, PlayerDataListener<T> listener) {
        playerListeners.computeIfAbsent(field.fullKey(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <K, T> void subscribeLinked(DataField<T> field, LinkedDataListener<K, T> listener) {
        linkedListeners.computeIfAbsent(field.fullKey(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <K> void subscribeLinkChange(LinkType<K> type, LinkChangeListener<K> listener) {
        linkChangeListeners.computeIfAbsent(type.name(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <T> void subscribeExpiration(ExpiringField<T> field, ExpirationListener<T> listener) {
        expirationListeners.computeIfAbsent(field.fullKey(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <K, T> void subscribeLinkedExpiration(ExpiringLinkedField<K, T> field, LinkedExpirationListener<K, T> listener) {
        linkedExpirationListeners.computeIfAbsent(field.fullKey(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @SuppressWarnings("unchecked")
    public <T> void firePlayerDataChanged(DataField<T> field, UUID player, T oldValue, T newValue) {
        List<PlayerDataListener<?>> listeners = playerListeners.get(field.fullKey());
        if (listeners != null) {
            for (PlayerDataListener<?> listener : listeners) {
                ((PlayerDataListener<T>) listener).onChanged(player, oldValue, newValue);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K, T> void fireLinkedDataChanged(DataField<T> field, K linkKey, T oldValue, T newValue, Set<UUID> affected) {
        List<LinkedDataListener<?, ?>> listeners = linkedListeners.get(field.fullKey());
        if (listeners != null) {
            for (LinkedDataListener<?, ?> listener : listeners) {
                ((LinkedDataListener<K, T>) listener).onChanged(linkKey, oldValue, newValue, affected);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K> void fireLinked(LinkType<K> type, UUID player, K linkKey) {
        List<LinkChangeListener<?>> listeners = linkChangeListeners.get(type.name());
        if (listeners != null) {
            for (LinkChangeListener<?> listener : listeners) {
                ((LinkChangeListener<K>) listener).onLinked(player, type, linkKey);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K> void fireUnlinked(LinkType<K> type, UUID player, K previousKey) {
        List<LinkChangeListener<?>> listeners = linkChangeListeners.get(type.name());
        if (listeners != null) {
            for (LinkChangeListener<?> listener : listeners) {
                ((LinkChangeListener<K>) listener).onUnlinked(player, type, previousKey);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void fireExpired(ExpiringField<T> field, UUID playerId, T expiredValue) {
        List<ExpirationListener<?>> listeners = expirationListeners.get(field.fullKey());
        if (listeners != null) {
            for (ExpirationListener<?> listener : listeners) {
                ((ExpirationListener<T>) listener).onExpired(playerId, field, expiredValue);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K, T> void fireLinkedExpired(ExpiringLinkedField<K, T> field, K linkKey, T expiredValue, Set<UUID> memberIds) {
        List<LinkedExpirationListener<?, ?>> listeners = linkedExpirationListeners.get(field.fullKey());
        if (listeners != null) {
            for (LinkedExpirationListener<?, ?> listener : listeners) {
                ((LinkedExpirationListener<K, T>) listener).onExpired(linkKey, field, expiredValue, memberIds);
            }
        }
    }
}
