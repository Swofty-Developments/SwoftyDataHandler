package net.swofty.data;

import java.util.Map;

public interface DataFormat {
    DataReader createReader(byte[] data);
    DataWriter createWriter();
    byte[] toBytes(DataWriter writer);

    /**
     * Parses a stored document into its top-level {@code fullKey -> value} map.
     * Used to merge a partial in-memory view back over the full stored document so
     * fields that were never read this session are not dropped on save. Formats that
     * are not keyed (e.g. a purely sequential binary format) may leave this
     * unsupported, in which case they cannot back multi-field container storage.
     */
    default Map<String, Object> readRaw(byte[] data) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support whole-document (readRaw) access");
    }

    /** Serializes a top-level {@code fullKey -> value} map back into stored bytes. */
    default byte[] writeRaw(Map<String, Object> data) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support whole-document (writeRaw) access");
    }
}
