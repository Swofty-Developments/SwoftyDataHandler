package net.swofty.storage;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.resps.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RedisDataStorage implements DataStorage, LeaderboardIndex {
    private final JedisPool pool;
    private final String prefix;

    public RedisDataStorage(JedisPool pool) {
        this(pool, "swofty:data");
    }

    public RedisDataStorage(JedisPool pool, String prefix) {
        this.pool = pool;
        this.prefix = prefix;
    }

    public RedisDataStorage(String host, int port) {
        this(new JedisPool(new JedisPoolConfig(), host, port));
    }

    private byte[] dataKey(String type, String id) {
        return (prefix + ":" + type + ":" + id).getBytes(StandardCharsets.UTF_8);
    }

    private String indexKey(String type) {
        return prefix + ":index:" + type;
    }

    @Override
    public byte[] load(String type, String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(dataKey(type, id));
        }
    }

    @Override
    public void save(String type, String id, byte[] data) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(dataKey(type, id), data);
            jedis.sadd(indexKey(type), id);
        }
    }

    @Override
    public List<String> listIds(String type) {
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.smembers(indexKey(type)));
        }
    }

    @Override
    public void delete(String type, String id) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(dataKey(type, id));
            jedis.srem(indexKey(type), id);
        }
    }

    @Override
    public boolean exists(String type, String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(dataKey(type, id));
        }
    }

    // ---- LeaderboardIndex (Redis sorted sets) -------------------------------

    private String leaderboardKey(String leaderboard) {
        return prefix + ":lb:" + leaderboard;
    }

    @Override
    public void updateScore(String leaderboard, String id, double score) {
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(leaderboardKey(leaderboard), score, id);
        }
    }

    @Override
    public void removeFromLeaderboard(String leaderboard, String id) {
        try (Jedis jedis = pool.getResource()) {
            jedis.zrem(leaderboardKey(leaderboard), id);
        }
    }

    @Override
    public List<ScoreEntry> scoreRange(String leaderboard, int start, int endInclusive, boolean descending) {
        String key = leaderboardKey(leaderboard);
        try (Jedis jedis = pool.getResource()) {
            List<Tuple> tuples = descending
                    ? jedis.zrevrangeWithScores(key, start, endInclusive)
                    : jedis.zrangeWithScores(key, start, endInclusive);
            List<ScoreEntry> result = new ArrayList<>(tuples.size());
            for (Tuple tuple : tuples) {
                result.add(new ScoreEntry(tuple.getElement(), tuple.getScore()));
            }
            return result;
        }
    }

    @Override
    public long leaderboardSize(String leaderboard) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.zcard(leaderboardKey(leaderboard));
        }
    }

    public JedisPool getPool() {
        return pool;
    }

    public void close() {
        pool.close();
    }
}
