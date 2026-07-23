package net.swofty.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDataStorage implements DataStorage, LeaderboardIndex {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> leaderboards = new ConcurrentHashMap<>();

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

    // ---- LeaderboardIndex ---------------------------------------------------

    @Override
    public void updateScore(String leaderboard, String id, double score) {
        leaderboards.computeIfAbsent(leaderboard, k -> new ConcurrentHashMap<>()).put(id, score);
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
