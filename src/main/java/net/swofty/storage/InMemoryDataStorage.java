package net.swofty.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryDataStorage implements DataStorage, LeaderboardIndex {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<StorageKey, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> leaderboards = new ConcurrentHashMap<>();

    @Override
    public byte[] load(String type, String id) {
        Map<String, byte[]> bucket = data.get(type);
        return bucket == null ? null : bucket.get(id);
    }

    @Override
    public VersionedData loadVersioned(String type, String id) {
        StorageKey key = new StorageKey(type, id);
        AtomicLong version = versions.get(key);
        if (version == null) return new VersionedData(load(type, id), 0L);
        synchronized (version) { return new VersionedData(load(type, id), version.get()); }
    }

    @Override
    public CompletionStage<SaveResult> save(String type, String id, byte[] bytes) {
        StorageKey key = new StorageKey(type, id);
        long version;
        synchronized (versions.computeIfAbsent(key, ignored -> new AtomicLong())) {
            data.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(id, Arrays.copyOf(bytes, bytes.length));
            version = versions.get(key).incrementAndGet();
        }
        return CompletableFuture.completedFuture(SaveResult.saved(type, id, version, bytes.length));
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
            versions.remove(new StorageKey(type, id));
        }
    }

    @Override
    public boolean exists(String type, String id) {
        ConcurrentHashMap<String, byte[]> bucket = data.get(type);
        return bucket != null && bucket.containsKey(id);
    }

    // ---- LeaderboardIndex ---------------------------------------------------

    @Override
    public void updateScore(String leaderboard, String id, double score) {
        leaderboards.computeIfAbsent(leaderboard, k -> new ConcurrentHashMap<>()).put(id, score);
    }

    @Override
    public void updateScoreIfPresent(String leaderboard, String id, double score) {
        ConcurrentHashMap<String, Double> board = leaderboards.get(leaderboard);
        if (board != null) {
            board.put(id, score);
        }
    }

    @Override
    public boolean leaderboardExists(String leaderboard) {
        ConcurrentHashMap<String, Double> board = leaderboards.get(leaderboard);
        return board != null && !board.isEmpty();
    }

    @Override
    public void removeFromLeaderboard(String leaderboard, String id) {
        ConcurrentHashMap<String, Double> board = leaderboards.get(leaderboard);
        if (board != null) {
            board.remove(id);
        }
    }

    @Override
    public List<ScoreEntry> scoreRange(String leaderboard, int start, int endInclusive, boolean descending) {
        ConcurrentHashMap<String, Double> board = leaderboards.get(leaderboard);
        if (board == null) {
            return List.of();
        }
        List<ScoreEntry> sorted = new ArrayList<>();
        board.forEach((id, score) -> sorted.add(new ScoreEntry(id, score)));
        sorted.sort(descending
                ? Comparator.comparingDouble(ScoreEntry::score).reversed()
                : Comparator.comparingDouble(ScoreEntry::score));
        if (start >= sorted.size() || start < 0) {
            return List.of();
        }
        int end = Math.min(endInclusive, sorted.size() - 1);
        return new ArrayList<>(sorted.subList(start, end + 1));
    }

    @Override
    public long leaderboardSize(String leaderboard) {
        ConcurrentHashMap<String, Double> board = leaderboards.get(leaderboard);
        return board == null ? 0 : board.size();
    }
}
