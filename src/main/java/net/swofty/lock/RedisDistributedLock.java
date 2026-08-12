package net.swofty.lock;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Redis-backed {@link DistributedLock} using {@code SET key token NX PX <lease>} to acquire
 * and a compare-and-delete Lua script to release, so a lock is only ever released by its owner.
 * A lease time bounds how long a crashed holder can block others. Not reentrant across nodes:
 * re-acquiring a key already held (even by the same thread) blocks until the lease or timeout.
 *
 * <p>A held lock renews its lease in the background, so work that legitimately outlives the lease
 * does not silently lose the lock halfway through. Renewal can still fail — the process stalls, the
 * connection breaks, the key is force-deleted — which is what {@link Handle#isValid()} and
 * {@link Handle#ensureValid()} report. That check is best-effort by construction: it tells you the
 * lease was still held a moment ago, not that it will still be held while the next write lands.
 * {@link Handle#fencingToken()} is published for callers that need a real guarantee and can reject
 * stale writes at the storage layer; this library does not enforce it on its own writes.
 */
public class RedisDistributedLock implements DistributedLock, AutoCloseable {
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final String ACQUIRE_SCRIPT =
            "if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then "
            + "local fence = redis.call('incr', KEYS[2]); redis.call('expire', KEYS[2], ARGV[3]); "
            + "return fence else return 0 end";
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";

    // The fence counter has to outlive every holder that could still be in flight, but not the
    // cluster: an un-expiring counter per lock key is a permanent leak in a keyspace with one key
    // per player.
    private static final long FENCE_TTL_MULTIPLIER = 100L;

    private final JedisPool pool;
    private final String prefix;
    private final Duration leaseTime;
    private final long retryDelayMillis;
    private final ScheduledThreadPoolExecutor renewals;

    public RedisDistributedLock(JedisPool pool) {
        this(pool, "swofty:lock", Duration.ofSeconds(30), 50);
    }

    public RedisDistributedLock(JedisPool pool, String prefix, Duration leaseTime, long retryDelayMillis) {
        this.pool = pool;
        this.prefix = prefix;
        this.leaseTime = leaseTime;
        this.retryDelayMillis = retryDelayMillis;
        this.renewals = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "swofty-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
        // Without this a cancelled renewal sits in the queue until its next scheduled run, so a
        // busy key leaves a growing pile of dead tasks behind every acquire.
        this.renewals.setRemoveOnCancelPolicy(true);
    }

    @Override
    public Handle acquire(String key, Duration timeout) {
        String redisKey = prefix + ":" + key;
        String fenceKey = prefix + ":fence:" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + timeout.toNanos();
        String leaseMillis = Long.toString(leaseTime.toMillis());
        String fenceTtlSeconds = Long.toString(Math.max(1L, leaseTime.toSeconds() * FENCE_TTL_MULTIPLIER));

        while (true) {
            try (Jedis jedis = pool.getResource()) {
                Object acquired = jedis.eval(ACQUIRE_SCRIPT, List.of(redisKey, fenceKey),
                        List.of(token, leaseMillis, fenceTtlSeconds));
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

        @Override
        public long fencingToken() {
            return fence;
        }

        @Override
        public boolean renew() {
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

        @Override
        public boolean isValid() {
            return valid.get();
        }

        @Override
        public void close() {
            renewal.cancel(false);
            if (valid.getAndSet(false)) {
                release(redisKey, token);
            }
        }
    }

    /** Stops the renewal thread. Held handles stop renewing and expire with their lease. */
    @Override
    public void close() {
        renewals.shutdownNow();
    }
}
