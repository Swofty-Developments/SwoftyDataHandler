package net.swofty.storage;

/** Confirmed outcome of one durable write. */
public record SaveResult(StorageKey key, long version, int bytesWritten, Status status) {
    public enum Status { SAVED, UNCHANGED }

    public static SaveResult saved(String type, String id, long version, int bytes) {
        return new SaveResult(new StorageKey(type, id), version, bytes, Status.SAVED);
    }

    public static SaveResult unchanged(String type, String id, long version) {
        return new SaveResult(new StorageKey(type, id), version, 0, Status.UNCHANGED);
    }

    public boolean saved() { return status == Status.SAVED; }
}
