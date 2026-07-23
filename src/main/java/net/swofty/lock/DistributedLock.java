package net.swofty.lock;

import java.time.Duration;

/**
 * A mutual-exclusion lock that spans server instances. Supplying one to the API upgrades
 * transactions from a JVM-local lock (which only serialises threads within a single process)
 * to true cross-node mutual exclusion — required when the same entity (e.g. a coop or island
 * shared across servers) can be mutated from more than one node at once.
 */
public interface DistributedLock {

    /**
     * Acquires the named lock, blocking up to {@code timeout}. The returned handle must be
     * closed to release the lock; use it with try-with-resources. Throws
     * {@link LockAcquisitionException} if the lock cannot be acquired within the timeout.
     */
    Handle acquire(String key, Duration timeout);

    /** A held lock. Closing it releases the lock and never throws a checked exception. */
    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
