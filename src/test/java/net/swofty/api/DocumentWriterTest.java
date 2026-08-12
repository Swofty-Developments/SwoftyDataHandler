package net.swofty.api;

import net.swofty.PlayerField;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.InMemoryDataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.WriteConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compare-and-set retry policy on its own, without an API around it.
 *
 * <p>Retrying is the right answer to contention, where a conflict means someone else committed and
 * the retry follows them. It is not an answer to a backend whose reads and writes disagree about
 * what is stored — a failover to a lagging replica, say — where the reread hands back a version the
 * write then rejects, forever. That has to end, and it has to end without forcing the document over
 * whatever is really there.
 */
class DocumentWriterTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("writer", "coins", Codecs.INT, 0);
    private static final JsonFormat FORMAT = new JsonFormat();

    private static DataContainer containerHolding(int coins) {
        DataContainer container = new DataContainer();
        container.loadDocument(FORMAT, null, 0L);
        container.set(COINS, coins);
        return container;
    }

    @Test
    @Timeout(60)
    void aBackendThatNeverAcceptsAWriteFailsLoudlyInsteadOfSpinning() {
        class NeverAccepts extends InMemoryDataStorage {
            final AtomicInteger conditionalWrites = new AtomicInteger();

            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                conditionalWrites.incrementAndGet();
                return SaveResult.conflict(type, id, expectedVersion + 5);
            }
        }

        NeverAccepts storage = new NeverAccepts();
        String id = UUID.randomUUID().toString();

        WriteConflictException failure = assertThrows(WriteConflictException.class,
                () -> DocumentWriter.write(storage, FORMAT, "players", id, containerHolding(7),
                        Duration.ofMillis(400)));

        assertEquals("players", failure.key().type());
        assertEquals(id, failure.key().id());
        assertTrue(failure.attempts() > 1, "gave up on the first attempt");
        assertEquals(failure.attempts(), storage.conditionalWrites.get());
        // The point of giving up: the document is left exactly as the backend has it.
        assertFalse(storage.exists("players", id), "the write was forced over the stored document");
    }

    @Test
    @Timeout(60)
    void aWriteLandsAsSoonAsTheBackendStopsRejectingIt() {
        class RejectsForABit extends InMemoryDataStorage {
            private int remaining = 6;

            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                if (remaining-- > 0) return SaveResult.conflict(type, id, super.loadVersioned(type, id).version());
                return super.saveIfVersion(type, id, data, expectedVersion);
            }
        }

        RejectsForABit storage = new RejectsForABit();
        String id = UUID.randomUUID().toString();

        SaveResult result = DocumentWriter.write(storage, FORMAT, "players", id, containerHolding(7),
                Duration.ofSeconds(30));

        assertTrue(result.saved());
        assertTrue(storage.exists("players", id));
    }
}
