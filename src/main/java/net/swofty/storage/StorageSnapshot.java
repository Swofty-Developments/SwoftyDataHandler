package net.swofty.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record StorageSnapshot(Instant createdAt, List<SaveRequest> documents) {
    public StorageSnapshot {
        Objects.requireNonNull(createdAt, "createdAt");
        documents = List.copyOf(documents);
    }

    public static StorageSnapshot of(List<SaveRequest> documents) {
        return new StorageSnapshot(Instant.now(), documents);
    }
}
