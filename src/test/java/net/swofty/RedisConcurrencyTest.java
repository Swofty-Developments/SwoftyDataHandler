package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.storage.RedisDataStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compare-and-set path against a real Redis, where the version, the comparison and the write
 * are a script running on the server rather than a monitor inside one JVM.
 */
class RedisConcurrencyTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("redis_cas", "coins", Codecs.INT, 0);
    private static final PlayerField<Long> PLAYTIME = PlayerField.create("redis_cas", "playtime", Codecs.LONG, 0L);

    private static JedisPool pool;
    private static final String PREFIX = "swofty:test:cas:" + System.currentTimeMillis();

    @BeforeAll
    static void checkRedis() {
        try {
            pool = new JedisPool("localhost", 6379);
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
        } catch (Exception unavailable) {
            pool = null;
        }
        Assumptions.assumeTrue(pool != null, "Redis not available, skipping");
    }

    @AfterAll
    static void cleanUp() {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            for (String key : jedis.keys(PREFIX + "*")) {
                jedis.del(key);
            }
        }
        pool.close();
    }

    @Test
    @Timeout(120)
    void twoNodesWritingDifferentFieldsOverRedisKeepBothFields() throws Exception {
        RedisDataStorage storage = new RedisDataStorage(pool, PREFIX);
        DataAPI a = new DataAPIImpl(storage);
        DataAPI b = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            a.load(player);
            b.load(player);

            CompletableFuture<Void> coins = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 150; i++) a.update(player, COINS, value -> value + 1);
            }, executor);
            CompletableFuture<Void> playtime = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 150; i++) b.update(player, PLAYTIME, value -> value + 1);
            }, executor);
            CompletableFuture.allOf(coins, playtime).get(90, TimeUnit.SECONDS);

            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(150, fresh.get(player, COINS));
                assertEquals(150L, fresh.get(player, PLAYTIME));
            }
        } finally {
            a.shutdown();
            b.shutdown();
            executor.shutdownNow();
        }
    }
}
