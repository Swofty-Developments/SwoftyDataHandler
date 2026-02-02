package net.swofty.storage;

import java.util.List;

public interface DataStorage {
    byte[] load(String type, String id);
    void save(String type, String id, byte[] data);
    List<String> listIds(String type);
    void delete(String type, String id);
    boolean exists(String type, String id);

    /**
     * Whether this storage backend supports event listeners.
     * Distributed backends (e.g. Redis) return true; local-only backends (e.g. file) return false.
     */
    default boolean supportsListeners() {
        return false;
    }
}
