package net.swofty.storage;

import java.util.Arrays;

public record VersionedData(byte[] data, long version) {
    public VersionedData { data = data == null ? null : Arrays.copyOf(data, data.length); }
    @Override public byte[] data() { return data == null ? null : Arrays.copyOf(data, data.length); }
}
