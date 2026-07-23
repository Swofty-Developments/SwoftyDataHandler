package net.swofty.lock;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * A Redis-backed {@link DistributedLock} using {@code SET key token NX PX <lease>} to acquire
 * and a compare-and-delete Lua script to release, so a lock is only ever released by its owner.
 * A lease time bounds how long a crashed holder can block others. Not reentrant across nodes:
 * re-acquiring a key already held (even by the same thread) blocks until the lease or timeout.
 */
public class RedisDistributedLock implements DistributedLock {
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final JedisPool pool;
    private final String prefix;
    private final Duration leaseTime;
    private final long retryDelayMillis;

    public RedisDistributedLock(JedisPool pool) {
        this(pool, "swofty:lock", Duration.ofSeconds(30), 50);
    }

    public RedisDistributedLock(JedisPool pool, String prefix, Duration leaseTime, long retryDelayMillis) {
        this.pool = pool;
        this.prefix = prefix;
        this.leaseTime = leaseTime;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public Handle acquire(String key, Duration timeout) {
        String redisKey = prefix + ":" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + timeout.toNanos();
        SetParams params = new SetParams().nx().px(leaseTime.toMillis());

        while (true) {
            try (Jedis jedis = pool.getResource()) {
                if ("OK".equals(jedis.set(redisKey, token, params))) {
                    return () -> release(redisKey, token);
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new LockAcquisitionException("Timed out acquiring lock: " + key);
            }
            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionException("Interrupted acquiring lock: " + key);
            }
        }
    }

    private void release(String redisKey, String token) {
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(UNLOCK_SCRIPT, Collections.singletonList(redisKey), Collections.singletonList(token));
        }
    }
}
