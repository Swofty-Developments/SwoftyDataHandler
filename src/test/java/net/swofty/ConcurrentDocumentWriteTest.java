package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.storage.InMemoryDataStorage;
import net.swofty.storage.SaveResult;
import net.swofty.storage.VersionedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    /** Lets a peer's write land at a chosen point inside another node's retry loop. */
    private static final class InterleavingStorage extends InMemoryDataStorage {
        final AtomicInteger conditionalWrites = new AtomicInteger();
        private volatile int conflictsToForce;
        private volatile int rereadsBeforeHook = -1;
        private volatile Runnable hook;

        void forceConflicts(int count) {
            conflictsToForce = count;
        }

        /** Runs {@code action} after the Nth reread returns, i.e. between a reread and the write. */
        void afterReread(int reread, Runnable action) {
            rereadsBeforeHook = reread;
            hook = action;
        }

        @Override
        public VersionedData loadVersioned(String type, String id) {
            VersionedData loaded = super.loadVersioned(type, id);
            if (rereadsBeforeHook > 0 && --rereadsBeforeHook == 0) {
                Runnable action = hook;
                hook = null;
                if (action != null) action.run();
            }
            return loaded;
        }

        @Override
        public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
            conditionalWrites.incrementAndGet();
            if (conflictsToForce > 0) {
                conflictsToForce--;
                return SaveResult.conflict(type, id, super.loadVersioned(type, id).version());
            }
            return super.saveIfVersion(type, id, data, expectedVersion);
        }
    }

    @Test
    @Timeout(60)
    void aPeersWriteIsNotErasedByANodeThatHasBeenLosingRaces() {
        InterleavingStorage storage = new InterleavingStorage();
        CapturedLog log = CapturedLog.attachTo("net.swofty.api.DocumentWriter");
        DataAPI a = new DataAPIImpl(storage);
        DataAPI b = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        try {
            a.load(player);
            b.load(player);
            b.set(player, NAME, "peer-1");

            // Node A loses twenty races, and on the last reread before it finally writes, the peer
            // lands another write to a different field. Giving up and overwriting at any point here
            // erases that field - which is exactly the failure the comparison exists to prevent, and
            // it is likeliest precisely when contention has been high enough to make a node give up.
            storage.forceConflicts(20);
            storage.afterReread(20, () -> b.set(player, NAME, "peer-2"));
            a.set(player, COINS, 42);

            assertTrue(storage.conditionalWrites.get() > 20,
                    "the write must keep retrying rather than stopping at a threshold");
            assertTrue(log.warned(), "sustained contention must be visible in the log");

            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(42, fresh.get(player, COINS));
                assertEquals("peer-2", fresh.get(player, NAME), "the peer's write was overwritten");
            }
        } finally {
            a.shutdown();
            b.shutdown();
            log.detach();
        }
    }

    @Test
    @Timeout(60)
    void aWriteThatKeepsLosingEventuallyLandsWithoutOverwritingAnything() {
        InterleavingStorage storage = new InterleavingStorage();
        DataAPI api = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        try {
            storage.forceConflicts(40);
            api.set(player, COINS, 7);

            assertEquals(41, storage.conditionalWrites.get());
            try (DataAPI fresh = new DataAPIImpl(storage)) {
                assertEquals(7, fresh.get(player, COINS));
            }
        } finally {
            api.shutdown();
        }
    }

    // Stands in for what a real backend throws when it cannot reach the server, e.g. Jedis'
    // JedisConnectionException: a plain RuntimeException, not one of the API's own contract types.
    private static final class StorageUnavailableException extends RuntimeException {
        StorageUnavailableException() {
            super("redis down");
        }
    }

    @Test
    void aStorageFailureOnWriteReachesTheCallerSynchronously() {
        class BrokenStorage extends InMemoryDataStorage {
            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                throw new StorageUnavailableException();
            }
        }

        DataAPI api = new DataAPIImpl(new BrokenStorage());
        UUID player = UUID.randomUUID();

        // A write that never reached storage has to be a failure the caller sees, on the thread
        // that asked for it. Handing back a future nobody looks at turns an outage into silence.
        assertEquals("redis down",
                assertThrows(StorageUnavailableException.class, () -> api.set(player, COINS, 1)).getMessage());
        assertEquals("redis down",
                assertThrows(StorageUnavailableException.class, () -> api.update(player, COINS, c -> c + 1)).getMessage());
        assertThrows(StorageUnavailableException.class, api::shutdown);
    }

    @Test
    void aStorageFailureOnADeferredFlushReachesTheCallerSynchronously() {
        class BrokenStorage extends InMemoryDataStorage {
            volatile boolean broken;

            @Override
            public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
                if (broken) throw new StorageUnavailableException();
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
                    assertThrows(StorageUnavailableException.class, () -> api.flush(player)).getMessage());
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
