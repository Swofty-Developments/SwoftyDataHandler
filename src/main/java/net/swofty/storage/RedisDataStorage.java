package net.swofty.storage;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.resps.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    private String versionKey(String type, String id) { return prefix + ":version:" + type + ":" + id; }
    private static final byte[] SAVE_SCRIPT = ("local v=redis.call('incr',KEYS[2]);" +
            "redis.call('set',KEYS[1],ARGV[1]);redis.call('sadd',KEYS[3],ARGV[2]);return v")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOAD_SCRIPT =
            "return {redis.call('get',KEYS[1]),redis.call('get',KEYS[2])}".getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] load(String type, String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(dataKey(type, id));
        }
    }

    @Override
    public CompletionStage<SaveResult> save(String type, String id, byte[] data) {
        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(SAVE_SCRIPT,
                    List.of(dataKey(type, id), versionKey(type, id).getBytes(StandardCharsets.UTF_8),
                            indexKey(type).getBytes(StandardCharsets.UTF_8)),
                    List.of(data, id.getBytes(StandardCharsets.UTF_8)));
            long version = ((Number) raw).longValue();
            return CompletableFuture.completedFuture(SaveResult.saved(type, id, version, data.length));
        }
    }

    @Override
    public VersionedData loadVersioned(String type, String id) {
        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(LOAD_SCRIPT,
                    List.of(dataKey(type, id), versionKey(type, id).getBytes(StandardCharsets.UTF_8)), List.of());
            @SuppressWarnings("unchecked") List<byte[]> values = (List<byte[]>) raw;
            byte[] data = values.get(0);
            byte[] version = values.get(1);
            return new VersionedData(data, version == null ? 0L : Long.parseLong(new String(version, StandardCharsets.UTF_8)));
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
            jedis.del(versionKey(type, id));
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

    private static final String ZADD_IF_EXISTS =
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('zadd', KEYS[1], ARGV[1], ARGV[2]) else return 0 end";

    @Override
    public void updateScore(String leaderboard, String id, double score) {
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(leaderboardKey(leaderboard), score, id);
        }
    }

    @Override
    public void updateScoreIfPresent(String leaderboard, String id, double score) {
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(ZADD_IF_EXISTS,
                    java.util.List.of(leaderboardKey(leaderboard)),
                    java.util.List.of(Double.toString(score), id));
        }
    }

    @Override
    public boolean leaderboardExists(String leaderboard) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(leaderboardKey(leaderboard));
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

    @Override public void close() {
        pool.close();
    }
}
