package net.swofty.lock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single-JVM {@link DistributedLock} backed by {@link ReentrantLock}. Useful for tests and
 * single-node deployments. Reentrant within a thread, unlike the Redis implementation, so it does
 * not reproduce the deadlock a real distributed lock would: code that must work against Redis has
 * to be tested against a non-reentrant lock.
 */
public class InMemoryDistributedLock implements DistributedLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    // One counter for the whole lock, not one per key: a fencing token only has to increase for
    // successive holders of the same key, and a per-key map would grow with the keyspace.
    private final AtomicLong fences = new AtomicLong();

    @Override
    public Handle acquire(String key, Duration timeout) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            if (!lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new LockAcquisitionException("Timed out acquiring lock: " + key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted acquiring lock: " + key);
        }
        long fence = fences.incrementAndGet();
        return new Handle() {
            private final AtomicBoolean valid = new AtomicBoolean(true);

            @Override
            public long fencingToken() {
                return fence;
            }

            @Override
            public boolean isValid() {
                return valid.get();
            }

            @Override
            public void close() {
                if (valid.compareAndSet(true, false)) {
                    lock.unlock();
                }
            }
        };
    }
}
