package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringLinkedField;
import net.swofty.LinkedField;
import net.swofty.data.DataFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.VersionedData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

class LinkedDataManager {
    private static final System.Logger LOGGER = System.getLogger(LinkedDataManager.class.getName());

    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final LinkRegistryImpl linkRegistry;
    private final ConcurrentHashMap<String, DataContainer> cache = new ConcurrentHashMap<>();
    private final EntityLocks locks = new EntityLocks();
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
        return locks.forKey(compositeKey);
    }

    static String compositeKey(String linkTypeName, Object key) {
        return linkTypeName + ":" + key;
    }

    static String storageType(String linkTypeName) {
        return "linked/" + linkTypeName;
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

    public <K, T> SaveResult set(UUID player, LinkedField<K, T> field, T value) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        return setDirect(linkKey, field, value);
    }

    public <K, T> SaveResult setDirect(K key, LinkedField<K, T> field, T value) {
        Validation.validate(field, value);
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            T oldValue = getFieldValue(field.linkType().name(), key, field);
            SaveResult saved = setFieldValue(field.linkType().name(), key, field, value);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), key);
            eventBus.fireLinkedDataChanged(field, key, oldValue, value, affected,
                    PlayerDataManager.eventVersion(saved));
            return saved;
        }
    }

    public <K, T> SaveResult update(UUID player, LinkedField<K, T> field, UnaryOperator<T> updater) {
        K linkKey = linkRegistry.resolve(player, field.linkType());
        if (linkKey == null) {
            throw new IllegalStateException("Player " + player + " is not linked to " + field.linkType().name());
        }
        return updateDirect(linkKey, field, updater);
    }

    public <K, T> SaveResult updateDirect(K key, LinkedField<K, T> field, UnaryOperator<T> updater) {
        String ck = compositeKey(field.linkType().name(), key);
        synchronized (getLock(ck)) {
            T oldValue = getFieldValue(field.linkType().name(), key, field);
            T newValue = updater.apply(oldValue);
            Validation.validate(field, newValue);
            SaveResult saved = setFieldValue(field.linkType().name(), key, field, newValue);
            Set<UUID> affected = linkRegistry.getLinkedPlayers(field.linkType(), key);
            eventBus.fireLinkedDataChanged(field, key, oldValue, newValue, affected,
                    PlayerDataManager.eventVersion(saved));
            return saved;
        }
    }

    <T> T getFieldValue(String linkTypeName, Object key, DataField<T> field) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = getContainer(ck);
        if (!container.has(field.fullKey())) {
            ensureDocumentLoaded(linkTypeName, key, container);
            container.ensureField(field, format);
        }
        return container.get(field);
    }

    <T> SaveResult setFieldValue(String linkTypeName, Object key, DataField<T> field, T value) {
        String ck = compositeKey(linkTypeName, key);
        DataContainer container = getContainer(ck);
        ensureDocumentLoaded(linkTypeName, key, container);
        container.set(field, value);
        if (autoPersist) {
            return persistLinked(linkTypeName, key, container);
        }
        return SaveResult.unchanged(storageType(linkTypeName), key.toString(), container.documentVersion());
    }

    private void ensureDocumentLoaded(String linkTypeName, Object key, DataContainer container) {
        if (!container.isDocumentLoaded()) {
            VersionedData loaded = storage.loadVersioned(storageType(linkTypeName), key.toString());
            container.loadDocument(format, loaded.data(), loaded.version());
            documentRead(linkTypeName, key, loaded.version());
        }
    }

    // Everything that produced the document just read is part of it, so any event at or below its
    // version is already applied here.
    private void documentRead(String linkTypeName, Object key, long version) {
        eventBus.rememberLinkedDocument(linkTypeName, key, version);
    }

    private SaveResult persistLinked(String linkTypeName, Object key, DataContainer container) {
        return DocumentWriter.write(storage, format, storageType(linkTypeName), key.toString(), container);
    }

    // ---- Lifecycle ----------------------------------------------------------

    /** Warms a shared entity's whole document into this node's cache in a single storage read. */
    public void loadLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            ensureDocumentLoaded(linkTypeName, key, getContainer(ck));
        }
    }

    public SaveResult flushLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = cache.get(ck);
            if (container != null && container.isDirty()) {
                SaveResult saved = persistLinked(linkTypeName, key, container);
                eventBus.fireLinkedSnapshotSaved(linkTypeName, key, saved.version());
                return saved;
            }
            return SaveResult.unchanged(storageType(linkTypeName), key.toString(),
                    container == null ? 0L : container.documentVersion());
        }
    }

    public SaveResult unloadLinked(String linkTypeName, Object key) {
        SaveResult result;
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = cache.get(ck);
            if (container != null && container.isDirty()) {
                result = persistLinked(linkTypeName, key, container);
                eventBus.fireLinkedSnapshotSaved(linkTypeName, key, result.version());
            } else {
                result = SaveResult.unchanged(storageType(linkTypeName), key.toString(),
                        container == null ? 0L : container.documentVersion());
            }
            cache.remove(ck);
        }
        eventBus.forgetLinked(linkTypeName, key);
        return result;
    }

    /** Drops the cached view without persisting it, for an entity whose document is gone. */
    void evict(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            cache.remove(ck);
        }
        eventBus.forgetLinked(linkTypeName, key);
    }

    /** Deletes the shared document itself and forgets everything cached about it here. */
    void deleteLinked(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            cache.remove(ck);
            storage.delete(storageType(linkTypeName), key.toString());
        }
        eventBus.forgetLinked(linkTypeName, key);
    }

    public boolean isLinkedLoaded(String linkTypeName, Object key) {
        return cache.containsKey(compositeKey(linkTypeName, key));
    }

    /**
     * Rereads a shared entity from storage, discarding this node's materialised fields. Only for a
     * caller holding the entity's exclusive (cross-node) lock: pending local writes are flushed
     * first so nothing buffered is lost, and the reread then picks up whatever another node wrote
     * since this node last looked. An entity not cached here needs nothing.
     */
    void refresh(String linkTypeName, Object key) {
        String ck = compositeKey(linkTypeName, key);
        synchronized (getLock(ck)) {
            DataContainer container = cache.get(ck);
            if (container == null) return;
            if (container.isDirty()) {
                persistLinked(linkTypeName, key, container);
            }
            VersionedData loaded = storage.loadVersioned(storageType(linkTypeName), key.toString());
            container.reload(loaded.data(), loaded.version());
            documentRead(linkTypeName, key, loaded.version());
        }
    }

    /** Flushes every cached shared entity. Used on shutdown so deferred writes are not lost. */
    public void flushAll() {
        for (String ck : cache.keySet()) {
            int colon = ck.indexOf(':');
            if (colon < 0) continue;
            flushLinked(ck.substring(0, colon), ck.substring(colon + 1));
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
        return storage.listIds(storageType(linkTypeName));
    }

    /** Rereads a shared entity another node has just flushed. Returns whether anything changed. */
    boolean applyRemoteSnapshot(String linkTypeName, String linkKey, long version) {
        String ck = compositeKey(linkTypeName, linkKey);
        DataContainer container = cache.get(ck);
        if (container == null) return false;
        synchronized (getLock(ck)) {
            container = cache.get(ck);
            if (container == null || version <= container.documentVersion()) return false;
            if (container.isDirty()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Not applying remote snapshot " + version + " for " + ck
                                + ": this node holds unsaved changes that would be discarded");
                return false;
            }
            VersionedData loaded = storage.loadVersioned(storageType(linkTypeName), linkKey);
            if (loaded.version() < version) return false;
            container.reload(loaded.data(), loaded.version());
            documentRead(linkTypeName, linkKey, loaded.version());
            return true;
        }
    }

    int cachedCount() {
        return cache.size();
    }
}
