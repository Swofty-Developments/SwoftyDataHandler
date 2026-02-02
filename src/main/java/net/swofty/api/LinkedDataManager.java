package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringLinkedField;
import net.swofty.LinkedField;
import net.swofty.data.DataFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class LinkedDataManager {
    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final LinkRegistryImpl linkRegistry;
    private final ConcurrentHashMap<String, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public LinkedDataManager(DataStorage storage, DataFormat format, EventBus eventBus, LinkRegistryImpl linkRegistry) {
        this.storage = storage;
        this.format = format;
        this.eventBus = eventBus;
        this.linkRegistry = linkRegistry;
    }

    Object getLock(String compositeKey) {
        return locks.computeIfAbsent(compositeKey, k -> new Object());
    }

    static String compositeKey(String linkTypeName, Object key) {
        return linkTypeName + ":" + key;
    }

    private DataContainer getContainer(String compositeKey) {
        return cache.computeIfAbsent(compositeKey, k -> new DataContainer());
    }

    public <K, T> T get(UUID player, LinkedField<K, T> field) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) return field.defaultValue();
        return getDirect(linkKey, field);
    }

    public <K, T> T getDirect(K key, LinkedField<K, T> field, ExpirationManager expiration) {
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            if (field instanceof ExpiringLinkedField<?, ?>) {
                if (expiration.isLinkedExpired(field.linkType().name(), key, field)) {
                    return field.defaultValue();
                }
            }
            return getFieldValue(field.linkType().name(), key, field);
        }
    }

    public <K, T> T getDirect(K key, LinkedField<K, T> field) {
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            return getFieldValue(field.linkType().name(), key, field);
        }
    }

    public <K, T> void set(UUID player, LinkedField<K, T> field, T value) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        setDirect(linkKey, field, value);
    }

    public <K, T> void setDirect(K key, LinkedField<K, T> field, T value) {
        Validation.validate(field, value);
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            T oldValue = getFieldValue(field.linkType().name(), key, field);
            setFieldValue(field.linkType().name(), key, field, value);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), key);
            eventBus.fireLinkedDataChanged(field, key, oldValue, value, affected);
        }
    }

    public <K, T> void update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        updateDirect(linkKey, field, updater);
    }

    public <K, T> void updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater) {
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            T oldValue = getFieldValue(field.linkType().name(), key, field);
            T newValue = updater.apply(oldValue);
            Validation.validate(field, newValue);
            setFieldValue(field.linkType().name(), key, field, newValue);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), key);
            eventBus.fireLinkedDataChanged(field, key, oldValue, newValue, affected);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T getFieldValue(String linkTypeName, Object key, DataField<T> field) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = getContainer(ck);
        if (!container.has(field.fullKey())) {
            byte[] raw = storage.load("linked/" + linkTypeName, key.toString());
            container.loadField(field, format, raw);
        }
        return container.get(field);
    }

    <T> void setFieldValue(String linkTypeName, Object key, DataField<T> field, T value) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = getContainer(ck);
        container.set(field, value);
        byte[] bytes = container.serialize(format);
        storage.save("linked/" + linkTypeName, key.toString(), bytes);
    }

    public List<String> listLinkedIds(String linkTypeName) {
        return storage.listIds("linked/" + linkTypeName);
    }
}
