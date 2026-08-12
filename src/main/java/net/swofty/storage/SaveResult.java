package net.swofty.storage;

/** Outcome of one durable write, including the version the document now carries. */
public record SaveResult(StorageKey key, long version, Status status) {
    public enum Status { SAVED, UNCHANGED, CONFLICT }

    public static SaveResult saved(String type, String id, long version) {
        return new SaveResult(new StorageKey(type, id), version, Status.SAVED);
    }

    public static SaveResult unchanged(String type, String id, long version) {
        return new SaveResult(new StorageKey(type, id), version, Status.UNCHANGED);
    }

    /** The stored document had moved on; {@code version} is what the backend holds instead. */
    public static SaveResult conflict(String type, String id, long currentVersion) {
        return new SaveResult(new StorageKey(type, id), currentVersion, Status.CONFLICT);
    }

    public boolean saved() {
        return status == Status.SAVED;
    }

    public boolean conflict() {
        return status == Status.CONFLICT;
    }
}
