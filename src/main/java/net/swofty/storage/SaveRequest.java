package net.swofty.storage;

import java.util.Arrays;
import java.util.Objects;

public record SaveRequest(StorageKey key, byte[] data) {
    public SaveRequest {
        Objects.requireNonNull(key, "key");
        data = Arrays.copyOf(Objects.requireNonNull(data, "data"), data.length);
    }

    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
}
