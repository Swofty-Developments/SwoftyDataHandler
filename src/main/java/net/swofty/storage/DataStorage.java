package net.swofty.storage;

import java.util.List;

public interface DataStorage extends AutoCloseable {
    byte[] load(String type, String id);
    void save(String type, String id, byte[] data);
    List<String> listIds(String type);
    void delete(String type, String id);
    boolean exists(String type, String id);

    /**
     * Reads a document together with the version the backend currently holds for it.
     *
     * <p>Both extension points below are defaulted so a storage written against 1.4.x keeps
     * compiling and keeps its old behaviour: it reports {@link VersionedData#UNVERSIONED}, which
     * makes every write below an unconditional last-writer-wins {@link #save}.
     */
    default VersionedData loadVersioned(String type, String id) {
        return new VersionedData(load(type, id), VersionedData.UNVERSIONED);
    }

    /**
     * Writes the document only if the stored version is still {@code expectedVersion}, atomically
     * within the backend, and reports the version the write produced.
     *
     * <p>A backend that cannot do the comparison atomically must not pretend otherwise: leaving
     * this defaulted is the honest answer and callers degrade to last-writer-wins rather than to
     * silent corruption.
     */
    default SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
        save(type, id, data);
        return SaveResult.saved(type, id, VersionedData.UNVERSIONED);
    }

    @Override
    default void close() {}
}
