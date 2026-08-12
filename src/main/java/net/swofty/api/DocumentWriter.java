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
 * <p>Retries are bounded. A document under permanent contention resolves as last-writer-wins with a
 * warning rather than spinning, because blocking a game thread forever is worse than losing a write
 * loudly.
 */
final class DocumentWriter {
    private static final System.Logger LOGGER = System.getLogger(DocumentWriter.class.getName());
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_BACKOFF_MILLIS = 8;

    private DocumentWriter() {}

    static SaveResult write(DataStorage storage, DataFormat format, String type, String id,
                            DataContainer container) {
        for (int attempt = 1; ; attempt++) {
            byte[] bytes = container.serialize(format);
            SaveResult result = storage.saveIfVersion(type, id, bytes, container.documentVersion());
            if (!result.conflict()) {
                container.markPersisted(bytes, result.version());
                return result;
            }
            VersionedData fresh = storage.loadVersioned(type, id);
            container.rebase(fresh.data(), fresh.version());
            if (attempt >= MAX_ATTEMPTS) {
                return overwrite(storage, format, type, id, container, attempt);
            }
            backOff(attempt);
        }
    }

    // Two nodes writing the same document in a loop spend most of each attempt serialising, so they
    // collide again and again in lockstep without this. A short randomised pause is what breaks the
    // symmetry and lets both of them land.
    private static void backOff(int attempt) {
        long millis = Math.min(MAX_BACKOFF_MILLIS, 1L << (attempt - 1));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, millis + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static SaveResult overwrite(DataStorage storage, DataFormat format, String type, String id,
                                        DataContainer container, int attempts) {
        LOGGER.log(System.Logger.Level.WARNING,
                "Gave up merging " + type + "/" + id + " after " + attempts
                        + " version conflicts; overwriting whatever is stored with this node's view");
        // Overwriting through the same conditional call, with the comparison waived, so the
        // version comes back from the write itself. Writing and then reading the version back would
        // pick up a version another node produced in between, and the container would then believe
        // it holds a document it has never seen.
        byte[] bytes = container.serialize(format);
        SaveResult result = storage.saveIfVersion(type, id, bytes, VersionedData.ANY_VERSION);
        container.markPersisted(bytes, result.version());
        return result;
    }
}
