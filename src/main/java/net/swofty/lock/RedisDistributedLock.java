package net.swofty.lock;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Redis-backed {@link DistributedLock} using {@code SET key token NX PX <lease>} to acquire
 * and a compare-and-delete Lua script to release, so a lock is only ever released by its owner.
 * A lease time bounds how long a crashed holder can block others. Not reentrant across nodes:
 * re-acquiring a key already held (even by the same thread) blocks until the lease or timeout.
 */
public class RedisDistributedLock implements DistributedLock, AutoCloseable {
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final String ACQUIRE_SCRIPT =
            "if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then " +
            "return redis.call('incr', KEYS[2]) else return 0 end";
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";

    private final JedisPool pool;
    private final String prefix;
    private final Duration leaseTime;
    private final long retryDelayMillis;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "swofty-lock-renewal");
        thread.setDaemon(true);
        return thread;
    });

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
        String fenceKey = prefix + ":fence:" + key;

        while (true) {
            try (Jedis jedis = pool.getResource()) {
                Object acquired = jedis.eval(ACQUIRE_SCRIPT, List.of(redisKey, fenceKey),
                        List.of(token, Long.toString(leaseTime.toMillis())));
                long fence = acquired instanceof Number number ? number.longValue() : 0L;
                if (fence > 0) {
                    return new RedisHandle(redisKey, token, fence);
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

    private final class RedisHandle implements Handle {
        private final String redisKey;
        private final String token;
        private final long fence;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final ScheduledFuture<?> renewal;

        private RedisHandle(String redisKey, String token, long fence) {
            this.redisKey = redisKey;
            this.token = token;
            this.fence = fence;
            long period = Math.max(1L, leaseTime.toMillis() / 3L);
            this.renewal = renewals.scheduleAtFixedRate(this::renew, period, period, TimeUnit.MILLISECONDS);
        }

        @Override public long fencingToken() { return fence; }

        @Override public boolean renew() {
            if (!valid.get()) return false;
            try (Jedis jedis = pool.getResource()) {
                Object renewed = jedis.eval(RENEW_SCRIPT, List.of(redisKey),
                        List.of(token, Long.toString(leaseTime.toMillis())));
                boolean owned = renewed instanceof Number number && number.longValue() == 1L;
                if (!owned) valid.set(false);
                return owned;
            } catch (RuntimeException failure) {
                valid.set(false);
                return false;
            }
        }

        @Override public boolean isValid() { return valid.get(); }

        @Override public void close() {
            boolean wasValid = valid.getAndSet(false);
            renewal.cancel(false);
            if (wasValid) release(redisKey, token);
        }
    }

    @Override public void close() { renewals.shutdownNow(); }
}
