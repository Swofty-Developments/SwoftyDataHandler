package net.swofty.api;

import net.swofty.DataField;
import net.swofty.data.DataFormat;
import net.swofty.data.DataReader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live, in-memory view of one entity's data on this node.
 *
 * <p>Only the fields actually read or written this session are materialised in
 * {@link #data}. To avoid clobbering fields that were never touched, the container
 * also retains the full {@link #backingDocument} it last saw in storage and, on
 * {@link #serialize(DataFormat)}, merges the touched fields over it. This is what
 * makes a partial write safe: previously an untouched field would be silently
 * dropped the first time any other field was persisted.
 */
class DataContainer {
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

    // Fields explicitly cleared (set to null) this session. Suppressed from the merged
    // output so a null-set actually deletes a field that still exists in the backing document.
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();

    // Fields this node has actually written and not yet persisted. Everything else in {@link #data}
    // was merely read, so it can be re-decoded from a fresher document instead of being replayed
    // over one; that distinction is what makes {@link #rebase} safe.
    private final Set<String> pendingWrites = ConcurrentHashMap.newKeySet();

    // The full serialized document as last seen in storage (null = no stored document).
    private volatile byte[] backingDocument;
    private volatile boolean documentLoaded;
    private volatile boolean dirty;
    private volatile long documentVersion;

    @SuppressWarnings("unchecked")
    public <T> T get(DataField<T> field) {
        Object value = data.get(field.fullKey());
        return value == null ? field.defaultValue() : (T) value;
    }

    public <T> void set(DataField<T> field, T value) {
        store(field, value);
        pendingWrites.add(field.fullKey());
        dirty = true;
    }

    private <T> void store(DataField<T> field, T value) {
        if (value == null) {
            data.remove(field.fullKey());
            tombstones.add(field.fullKey());
        } else {
            data.put(field.fullKey(), value);
            tombstones.remove(field.fullKey());
        }
    }

    public boolean has(String fullKey) {
        return data.containsKey(fullKey);
    }

    // ---- Document lifecycle -------------------------------------------------

    /** Records the full backing document so later partial writes do not drop untouched fields. */
    public void loadDocument(DataFormat format, byte[] raw) { loadDocument(format, raw, 0L); }

    public void loadDocument(DataFormat format, byte[] raw, long version) {
        this.backingDocument = raw;
        this.documentVersion = version;
        this.documentLoaded = true;
    }

    public boolean isDocumentLoaded() {
        return documentLoaded;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Lazily deserialises a single field out of the backing document into the live view. */
    public void ensureField(DataField<?> field, DataFormat format) {
        if (data.containsKey(field.fullKey()) || tombstones.contains(field.fullKey())) return;
        if (backingDocument == null) return;
        DataReader reader = format.createReader(backingDocument);
        if (reader.hasKey(field.fullKey())) {
            Object value = field.codec().read(reader.readSection(field.fullKey()));
            if (value != null) {
                data.put(field.fullKey(), value);
            }
        }
    }

    /** Back-compat entry point: warm the document (if needed) then pull a single field out of it. */
    public void loadField(DataField<?> field, DataFormat format, byte[] raw) {
        if (!documentLoaded) loadDocument(format, raw);
        ensureField(field, format);
    }

    /**
     * Replaces the whole cached view with a document just read from storage. Unlike
     * {@link #loadDocument(DataFormat, byte[])} this also discards the materialised fields, so a
     * value another node wrote is decoded afresh instead of being served from this node's copy.
     *
     * <p>The cached view is dropped, not merged: callers must hold the entity's exclusive lock and
     * must have flushed pending writes first, or those writes are lost.
     */
    public void reload(byte[] raw) { reload(raw, 0L); }

    public void reload(byte[] raw, long version) {
        data.clear();
        tombstones.clear();
        pendingWrites.clear();
        this.backingDocument = raw;
        this.documentVersion = version;
        this.documentLoaded = true;
        this.dirty = false;
    }

    /**
     * Rebases the pending writes onto a document that moved on underneath them, after a
     * compare-and-set write lost the race.
     *
     * <p>Unlike {@link #reload(byte[], long)} the unsaved edits survive: only the values that were
     * read (never written) are dropped, so the next {@link #serialize(DataFormat)} merges this
     * node's own changes over whatever the winner actually stored instead of over the stale picture
     * it started from.
     */
    public void rebase(byte[] raw, long version) {
        data.keySet().removeIf(key -> !pendingWrites.contains(key));
        tombstones.retainAll(pendingWrites);
        this.backingDocument = raw;
        this.documentVersion = version;
        this.documentLoaded = true;
    }

    /** Merges the touched fields over the backing document so nothing untouched is lost. */
    public byte[] serialize(DataFormat format) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (backingDocument != null) {
            merged.putAll(format.readRaw(backingDocument));
        }
        merged.putAll(data);
        for (String tombstone : tombstones) {
            merged.remove(tombstone);
        }
        return format.writeRaw(merged);
    }

    /**
     * Applies a value that another node has already persisted. Updates the live view so local
     * reads are fresh without marking the container dirty: the value is already in storage, and a
     * clean (e.g. read-only) container must not start re-persisting stale sibling fields on unload.
     *
     * <p>The backing document is patched with the same value, so it stays an accurate picture
     * of what is in storage: a later local write merges over a base that already carries the
     * remote change instead of resurrecting the value this node last saw.
     */
    public <T> void applyRemote(DataField<T> field, T value, DataFormat format) {
        store(field, value);
        patchBackingDocument(field.fullKey(), value, format);
    }

    // Stores the value exactly as serialize() would — the live object, written out by the
    // format's whole-document writer — so the patched document round-trips like any other.
    private void patchBackingDocument(String fullKey, Object value, DataFormat format) {
        if (!documentLoaded) return;
        Map<String, Object> document = backingDocument == null
                ? new LinkedHashMap<>()
                : format.readRaw(backingDocument);
        if (value == null) {
            document.remove(fullKey);
        } else {
            document.put(fullKey, value);
        }
        this.backingDocument = format.writeRaw(document);
    }

    /** Records the bytes just written to storage as the new backing document and clears the dirty flag. */
    public void markPersisted(byte[] bytes, long version) {
        this.backingDocument = bytes;
        this.documentVersion = Math.max(documentVersion, version);
        this.documentLoaded = true;
        this.dirty = false;
        pendingWrites.clear();
    }

    /**
     * The version of the document this container last read or wrote in full. A remote field patch
     * deliberately does not advance it: the patch proves one field moved, not that this node holds
     * the winner's whole document, so the next write still has to compare-and-set against the
     * version it really has.
     */
    public long documentVersion() {
        return documentVersion;
    }

    ConcurrentHashMap<String, Object> rawData() {
        return data;
    }
}
