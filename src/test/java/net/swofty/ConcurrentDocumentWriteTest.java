package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.storage.InMemoryDataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.VersionedData;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two nodes writing different fields of the same document is the ordinary case for a network of
 * game servers, and it used to lose data silently: each node serialises its own view over the base
 * it last read, so the second writer erased the first writer's field.
 */
class ConcurrentDocumentWriteTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("cas", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("cas", "name", Codecs.STRING, "");
    private static final PlayerField<Long> PLAYTIME = PlayerField.create("cas", "playtime", Codecs.LONG, 0L);

    @Test
    void writesToDifferentFieldsFromTwoNodesBothSurvive() {
        InMemoryDataStorage storage = new InMemoryDataStorage();
        // No pub/sub on purpose: the merge has to hold on storage alone, not because the nodes
        // gossiped their changes to each other first.
        DataAPI a = new DataAPIImpl(storage);
        DataAPI b = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        try {
            a.load(player);
            b.load(player);

            a.set(player, COINS, 500);
            b.set(player, NAME, "swofty");

            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(500, fresh.get(player, COINS));
                assertEquals("swofty", fresh.get(player, NAME));
            }
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void concurrentWritesToDifferentFieldsFromTwoNodesKeepBothFields() throws Exception {
        InMemoryDataStorage storage = new InMemoryDataStorage();
        DataAPI a = new DataAPIImpl(storage);
        DataAPI b = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            a.load(player);
            b.load(player);

            CompletableFuture<Void> coins = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 200; i++) a.update(player, COINS, value -> value + 1);
            }, executor);
            CompletableFuture<Void> playtime = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 200; i++) b.update(player, PLAYTIME, value -> value + 1);
            }, executor);
            CompletableFuture.allOf(coins, playtime).get(30, TimeUnit.SECONDS);

            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(200, fresh.get(player, COINS));
                assertEquals(200L, fresh.get(player, PLAYTIME));
            }
        } finally {
            a.shutdown();
            b.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void aDocumentUnderPermanentConflictFallsBackToLastWriterWinsAndSaysSo() {
        class AlwaysConflicting extends InMemoryDataStorage {
            final AtomicInteger conditionalWrites = new AtomicInteger();
            final AtomicInteger overwrites = new AtomicInteger();

            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                if (expectedVersion == VersionedData.ANY_VERSION) {
                    overwrites.incrementAndGet();
                    return super.saveIfVersion(type, id, data, expectedVersion);
                }
                conditionalWrites.incrementAndGet();
                return SaveResult.conflict(type, id, expectedVersion + 1);
            }
        }

        AlwaysConflicting storage = new AlwaysConflicting();
        CapturedLog log = CapturedLog.attachTo("net.swofty.api.DocumentWriter");
        DataAPI api = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        try {
            api.set(player, COINS, 7);

            // Bounded: it gives up rather than spinning on a document it can never win.
            assertEquals(8, storage.conditionalWrites.get());
            assertEquals(1, storage.overwrites.get());
            assertTrue(log.warned(), "giving up on a merge must be logged, not silent");

            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(7, fresh.get(player, COINS));
            }
        } finally {
            api.shutdown();
            log.detach();
        }
    }

    @Test
    void aStorageFailureOnWriteReachesTheCallerSynchronously() {
        class BrokenStorage extends InMemoryDataStorage {
            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                throw new IllegalStateException("redis down");
            }
        }

        DataAPI api = new DataAPIImpl(new BrokenStorage());
        UUID player = UUID.randomUUID();

        // A write that never reached storage has to be a failure the caller sees, on the thread
        // that asked for it. Handing back a future nobody looks at turns an outage into silence.
        assertEquals("redis down",
                assertThrows(IllegalStateException.class, () -> api.set(player, COINS, 1)).getMessage());
        assertEquals("redis down",
                assertThrows(IllegalStateException.class, () -> api.update(player, COINS, c -> c + 1)).getMessage());
        assertThrows(IllegalStateException.class, api::shutdown);
    }

    @Test
    void aStorageFailureOnADeferredFlushReachesTheCallerSynchronously() {
        class BrokenStorage extends InMemoryDataStorage {
            volatile boolean broken;

            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                if (broken) throw new IllegalStateException("redis down");
                return super.saveIfVersion(type, id, data, expectedVersion);
            }
        }

        BrokenStorage storage = new BrokenStorage();
        DataAPI api = new DataAPIImpl(storage, new net.swofty.data.format.JsonFormat(), null, false);
        UUID player = UUID.randomUUID();
        try {
            api.set(player, COINS, 1);
            storage.broken = true;
            assertEquals("redis down",
                    assertThrows(IllegalStateException.class, () -> api.flush(player)).getMessage());
        } finally {
            storage.broken = false;
            api.shutdown();
        }
    }

    /** Captures what the library logged through {@link System.Logger}, which the JDK routes to JUL. */
    private static final class CapturedLog {
        private final AtomicInteger warnings = new AtomicInteger();
        private final Logger logger;
        private final Handler handler;

        private CapturedLog(String name) {
            this.logger = Logger.getLogger(name);
            this.handler = new Handler() {
                @Override public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.incrementAndGet();
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        static CapturedLog attachTo(String name) {
            return new CapturedLog(name);
        }

        boolean warned() {
            return warnings.get() > 0;
        }

        void detach() {
            logger.removeHandler(handler);
        }
    }
}
