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

class LinkedDataManager {
    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final LinkRegistryImpl linkRegistry;
    private final ConcurrentHashMap<String, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final boolean autoPersist;

    public LinkedDataManager(DataStorage storage, DataFormat format, EventBus eventBus, LinkRegistryImpl linkRegistry) {
        this(storage, format, eventBus, linkRegistry, true);
    }

    public LinkedDataManager(DataStorage storage, DataFormat format, EventBus eventBus,
                             LinkRegistryImpl linkRegistry, boolean autoPersist) {
        this.storage = storage;
        this.format = format;
        this.eventBus = eventBus;
        this.linkRegistry = linkRegistry;
        this.autoPersist = autoPersist;
    }

    Object getLock(String compositeKey) {
        return locks.computeIfAbsent(compositeKey, k -> new Object());
    }

    static String compositeKey(String linkTypeName, Object key) {
        return linkTypeName + ":" + key;
    }

    DataContainer getContainer(String compositeKey) {
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
            ensureDocumentLoaded(linkTypeName, key, container);
            container.ensureField(field, format);
        }
        return container.get(field);
    }

    <T> void setFieldValue(String linkTypeName, Object key, DataField<T> field, T value) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = getContainer(ck);
        ensureDocumentLoaded(linkTypeName, key, container);
        container.set(field, value);
        if (autoPersist) {
            persistLinked(linkTypeName, key, container);
        }
    }

    private void ensureDocumentLoaded(String linkTypeName, Object key, DataContainer container) {
        if (!container.isDocumentLoaded()) {
            container.loadDocument(format, storage.load("linked/" + linkTypeName, key.toString()));
        }
    }

    private void persistLinked(String linkTypeName, String keyString, DataContainer container) {
        byte[] bytes = container.serialize(format);
        storage.save("linked/" + linkTypeName, keyString, bytes);
        container.markPersisted(bytes);
    }

    private void persistLinked(String linkTypeName, Object key, DataContainer container) {
        persistLinked(linkTypeName, key.toString(), container);
    }

    // ---- Lifecycle ----------------------------------------------------------

    /** Warms a shared entity's whole document into this node's cache in a single storage read. */
    public void loadLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = getContainer(ck);
            if (!container.isDocumentLoaded()) {
                container.loadDocument(format, storage.load("linked/" + linkTypeName, key.toString()));
            }
        }
    }

    public void flushLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = cache.get(ck);
            if (container != null && container.isDirty()) {
                persistLinked(linkTypeName, key, container);
            }
        }
    }

    public void unloadLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = cache.get(ck);
            if (container != null && container.isDirty()) {
                persistLinked(linkTypeName, key, container);
            }
            cache.remove(ck);
        }
        locks.remove(ck);
    }

    public boolean isLinkedLoaded(String linkTypeName, Object key) {
        return cache.containsKey(compositeKey(linkTypeName, key));
    }

    /** Flushes every cached shared entity. Used on shutdown so deferred writes are not lost. */
    public void flushAll() {
        for (String ck : cache.keySet()) {
            int colon = ck.indexOf(':');
            if (colon < 0) continue;
            String linkTypeName = ck.substring(0, colon);
            String keyString = ck.substring(colon + 1);
            synchronized (getLock(ck)) {
                DataContainer container = cache.get(ck);
                if (container != null && container.isDirty()) {
                    persistLinked(linkTypeName, keyString, container);
                }
            }
        }
    }

    /**
     * Applies a change that originated on another node to a locally cached shared entity,
     * without re-persisting or re-firing events. Only touches entities currently loaded here.
     */
    <T> void applyRemote(String linkTypeName, Object key, DataField<T> field, T newValue) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = cache.get(ck);
        if (container == null) return;
        synchronized (getLock(ck)) {
            container = cache.get(ck);
            if (container == null) return;
            container.applyRemote(field, newValue, format);
        }
    }

    public List<String> listLinkedIds(String linkTypeName) {
        return storage.listIds("linked/" + linkTypeName);
    }
}
