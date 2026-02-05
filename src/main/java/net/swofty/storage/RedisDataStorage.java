package net.swofty.storage;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RedisDataStorage implements DataStorage {
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

    public JedisPool getPool() {
        return pool;
    }

    public void close() {
        pool.close();
    }
}
