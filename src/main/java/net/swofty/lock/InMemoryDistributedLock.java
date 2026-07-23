package net.swofty.lock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single-JVM {@link DistributedLock} backed by {@link ReentrantLock}. Useful for tests and
 * single-node deployments; it does not coordinate across processes. Being reentrant, a thread
 * that already holds a key can acquire it again (e.g. a nested transaction on the same entity).
 */
public class InMemoryDistributedLock implements DistributedLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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
        return lock::unlock;
    }
}
