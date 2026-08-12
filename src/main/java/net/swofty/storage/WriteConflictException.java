package net.swofty.storage;

import java.time.Duration;

/**
 * A document could not be written because every attempt lost its compare-and-set, for long enough
 * that the library stopped trying.
 *
 * <p>Under ordinary contention this never surfaces: losing a race means another writer committed, so
 * a retry follows the winner and lands. Seeing this means the comparison never succeeded at all —
 * reads and writes disagreeing about what is stored, which is what a failover to a lagging replica
 * looks like — and no amount of further retrying would have fixed it. The write did not happen and
 * nothing was overwritten to pretend otherwise.
 */
public class WriteConflictException extends RuntimeException {
    private final StorageKey key;
    private final int attempts;

    public WriteConflictException(String type, String id, int attempts, Duration spent) {
        super("Could not write " + type + "/" + id + ": lost " + attempts
                + " compare-and-set attempts over " + spent.toMillis() + "ms."
                + " Storage is reporting a version its writes then reject, so the write was abandoned"
                + " rather than forced over whatever is stored");
        this.key = new StorageKey(type, id);
        this.attempts = attempts;
    }

    public StorageKey key() {
        return key;
    }

    public int attempts() {
        return attempts;
    }
}
