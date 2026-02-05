package net.swofty;

import net.swofty.storage.RedisDataStorage;
import org.junit.jupiter.api.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisDataStorageTest {

    private static JedisPool pool;
    private RedisDataStorage storage;
    private static final String PREFIX = "swofty:test:" + System.currentTimeMillis();

    @BeforeAll
    static void checkRedis() {
        try {
            pool = new JedisPool("localhost", 6379);
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
        } catch (Exception e) {
            pool = null;
        }
        Assumptions.assumeTrue(pool != null, "Redis not available, skipping");
    }

    @BeforeEach
    void setUp() {
        storage = new RedisDataStorage(pool, PREFIX);
    }

    @AfterEach
    void cleanUp() {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            for (String type : List.of("players", "guilds")) {
                for (String id : storage.listIds(type)) {
                    storage.delete(type, id);
                }
            }
        }
    }

    @AfterAll
    static void closePool() {
        if (pool != null) pool.close();
    }

    @Test
    void saveAndLoad() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        storage.save("players", "abc", data);
        assertArrayEquals(data, storage.load("players", "abc"));
    }

    @Test
    void loadNonExistentReturnsNull() {
        assertNull(storage.load("players", "missing"));
    }

    @Test
    void existsReturnsTrueAfterSave() {
        storage.save("players", "abc", new byte[]{1, 2, 3});
        assertTrue(storage.exists("players", "abc"));
    }

    @Test
    void existsReturnsFalseForMissing() {
        assertFalse(storage.exists("players", "nonexistent"));
    }

    @Test
    void deleteRemovesData() {
        storage.save("players", "abc", new byte[]{1});
        storage.delete("players", "abc");
        assertNull(storage.load("players", "abc"));
        assertFalse(storage.exists("players", "abc"));
    }

    @Test
    void listIdsReturnsAllSaved() {
        storage.save("players", "a", new byte[]{1});
        storage.save("players", "b", new byte[]{2});
        storage.save("players", "c", new byte[]{3});
        List<String> ids = storage.listIds("players");
        assertEquals(3, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertTrue(ids.contains("c"));
    }

    @Test
    void listIdsEmptyForMissingType() {
        assertTrue(storage.listIds("nonexistent").isEmpty());
    }

    @Test
    void differentTypesAreIsolated() {
        storage.save("players", "x", new byte[]{1});
        storage.save("guilds", "x", new byte[]{2});
        assertArrayEquals(new byte[]{1}, storage.load("players", "x"));
        assertArrayEquals(new byte[]{2}, storage.load("guilds", "x"));
    }

    @Test
    void saveOverwrites() {
        storage.save("players", "a", new byte[]{1});
        storage.save("players", "a", new byte[]{2});
        assertArrayEquals(new byte[]{2}, storage.load("players", "a"));
    }


    @Test
    void deleteRemovesFromIndex() {
        storage.save("players", "a", new byte[]{1});
        storage.save("players", "b", new byte[]{2});
        storage.delete("players", "a");
        List<String> ids = storage.listIds("players");
        assertEquals(1, ids.size());
        assertTrue(ids.contains("b"));
    }
}
