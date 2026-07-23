package net.swofty.lock;

/** Thrown when a {@link DistributedLock} cannot be acquired within its timeout. */
public class LockAcquisitionException extends RuntimeException {
    public LockAcquisitionException(String message) {
        super(message);
    }
}
