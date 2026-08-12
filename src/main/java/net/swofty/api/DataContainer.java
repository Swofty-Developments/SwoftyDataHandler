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

    // The full serialized document as last seen in storage (null = no stored document).
    private volatile byte[] backingDocument;
    private volatile boolean documentLoaded;
    private volatile boolean dirty;

    @SuppressWarnings("unchecked")
    public <T> T get(DataField<T> field) {
        Object value = data.get(field.fullKey());
        return value == null ? field.defaultValue() : (T) value;
    }

    public <T> void set(DataField<T> field, T value) {
        if (value == null) {
            data.remove(field.fullKey());
            tombstones.add(field.fullKey());
        } else {
            data.put(field.fullKey(), value);
            tombstones.remove(field.fullKey());
        }
        dirty = true;
    }

    public boolean has(String fullKey) {
        return data.containsKey(fullKey);
    }

    // ---- Document lifecycle -------------------------------------------------

    /** Records the full backing document so later partial writes do not drop untouched fields. */
    public void loadDocument(DataFormat format, byte[] raw) {
        this.backingDocument = raw;
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
     * Applies a value that another node has already persisted. Updates the live view so
     * local reads are fresh, but preserves the prior dirty state so a clean (e.g. read-only)
     * container does not get marked dirty and re-persist stale sibling fields on unload.
     *
     * <p>The backing document is patched with the same value, so it stays an accurate picture
     * of what is in storage: a later local write merges over a base that already carries the
     * remote change instead of resurrecting the value this node last saw.
     */
    public <T> void applyRemote(DataField<T> field, T value, DataFormat format) {
        boolean wasDirty = this.dirty;
        set(field, value);
        this.dirty = wasDirty;
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
    public void markPersisted(byte[] bytes) {
        this.backingDocument = bytes;
        this.documentLoaded = true;
        this.dirty = false;
    }

    ConcurrentHashMap<String, Object> rawData() {
        return data;
    }
}
