package net.swofty.storage;

import java.util.Objects;

public record StorageKey(String type, String id) {
    public StorageKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
