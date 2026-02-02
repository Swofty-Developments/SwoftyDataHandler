package net.swofty.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDataStorage implements DataStorage {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>> data = new ConcurrentHashMap<>();

    @Override
    public byte[] load(String type, String id) {
        Map<String, byte[]> bucket = data.get(type);
        return bucket == null ? null : bucket.get(id);
    }

    @Override
    public void save(String type, String id, byte[] bytes) {
        data.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(id, bytes);
    }

    @Override
    public List<String> listIds(String type) {
        ConcurrentHashMap<String, byte[]> bucket = data.get(type);
        return bucket == null ? List.of() : new ArrayList<>(bucket.keySet());
    }

    @Override
    public void delete(String type, String id) {
        ConcurrentHashMap<String, byte[]> bucket = data.get(type);
        if (bucket != null) {
            bucket.remove(id);
        }
    }

    @Override
    public boolean exists(String type, String id) {
        ConcurrentHashMap<String, byte[]> bucket = data.get(type);
        return bucket != null && bucket.containsKey(id);
    }

    @Override
    public boolean supportsListeners() {
        return true;
    }
}
