package net.swofty.lock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-JVM {@link DistributedLock} backed by {@link ReentrantLock}. Useful for tests and
 * single-node deployments; it does not coordinate across processes. Being reentrant, a thread
 * that already holds a key can acquire it again (e.g. a nested transaction on the same entity).
 */
public class InMemoryDistributedLock implements DistributedLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> fences = new ConcurrentHashMap<>();

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
        long fence = fences.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        return new Handle() {
            private volatile boolean valid = true;
            @Override public long fencingToken() { return fence; }
            @Override public boolean isValid() { return valid; }
            @Override public void close() { if (valid) { valid = false; lock.unlock(); } }
        };
    }
}
