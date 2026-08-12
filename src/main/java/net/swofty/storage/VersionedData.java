package net.swofty.storage;

import java.util.Arrays;

public record VersionedData(byte[] data, long version) {
    /** Reported by backends that do not track document versions. */
    public static final long UNVERSIONED = 0L;

    public VersionedData {
        data = data == null ? null : Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return data == null ? null : Arrays.copyOf(data, data.length);
    }
}
