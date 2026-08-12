package net.swofty.api;

import net.swofty.data.DataFormat;
import net.swofty.storage.DataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.VersionedData;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Persists one entity document with compare-and-set semantics.
 *
 * <p>Two nodes editing different fields of the same document used to be a silent data-loss bug: both
 * serialise their own view over the base they last read, and whoever writes second erases the other
 * one's field. The write is therefore conditional on the document version the container last read,
 * and a lost race is not an error — the container rebases its pending writes onto the winner's
 * document and tries again, so both fields survive.
 *
 * <p>Every write goes through that comparison, without exception. There is no give-up path that
 * overwrites the document unconditionally: whatever a peer wrote between this node's last reread and
 * its write would be erased wholesale, which is precisely the bug the comparison exists to prevent,
 * and it is worst exactly when contention is highest. The loop is unbounded because it does not need
 * a bound — every conflict means some other writer committed, so the system as a whole always makes
 * progress, and this node cannot spin unless work is actually being done to the document. It backs
 * off with jitter and complains at WARNING if it keeps losing.
 */
final class DocumentWriter {
    private static final System.Logger LOGGER = System.getLogger(DocumentWriter.class.getName());
    private static final int MAX_BACKOFF_MILLIS = 250;
    private static final int CONFLICTS_PER_WARNING = 16;

    private DocumentWriter() {}

    static SaveResult write(DataStorage storage, DataFormat format, String type, String id,
                            DataContainer container) {
        for (int conflicts = 0; ; conflicts++) {
            byte[] bytes = container.serialize(format);
            SaveResult result = storage.saveIfVersion(type, id, bytes, container.documentVersion());
            if (!result.conflict()) {
                container.markPersisted(bytes, result.version());
                return result;
            }
            if (conflicts > 0 && conflicts % CONFLICTS_PER_WARNING == 0) {
                LOGGER.log(System.Logger.Level.WARNING, "Still merging " + type + "/" + id + " after "
                        + conflicts + " version conflicts; this document is under heavy contention");
            }
            backOff(conflicts);
            // Reread last, so the document this node rebases onto is as fresh as the backoff allows.
            VersionedData fresh = storage.loadVersioned(type, id);
            container.rebase(fresh.data(), fresh.version());
        }
    }

    // Two nodes writing the same document in a loop spend most of each attempt serialising, so they
    // collide again and again in lockstep without this. A randomised pause is what breaks the
    // symmetry and lets both of them land.
    private static void backOff(int conflicts) {
        long ceiling = Math.min(MAX_BACKOFF_MILLIS, 1L << Math.min(conflicts, 10));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
