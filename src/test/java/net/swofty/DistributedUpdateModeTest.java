package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.lock.DistributedLock;
import net.swofty.lock.LockAcquisitionException;
import net.swofty.storage.InMemoryDataStorage;
import net.swofty.storage.StorageOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covered against a NON-reentrant lock on purpose. {@code InMemoryDistributedLock} is reentrant, so
 * it happily hands the same thread the same key twice and hides exactly the deadlock a Redis lock
 * would produce in production.
 */
class DistributedUpdateModeTest {

    /** Behaves like the Redis lock: one holder per key, no matter which thread asks. */
    private static final class NonReentrantLock implements DistributedLock {
        private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

        @Override
        public Handle acquire(String key, Duration timeout) {
            Semaphore permit = permits.computeIfAbsent(key, ignored -> new Semaphore(1));
            try {
                if (!permit.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new LockAcquisitionException("Timed out acquiring lock: " + key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionException("Interrupted acquiring lock: " + key);
            }
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) permit.release();
            };
        }
    }

    private static final PlayerField<Integer> COINS = PlayerField.create("mode", "coins", Codecs.INT, 0);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(250);

    private InMemoryDataStorage storage;
    private NonReentrantLock lock;
    private DataAPI api;

    @BeforeEach
    void setUp() {
        storage = new InMemoryDataStorage();
        lock = new NonReentrantLock();
        api = new DataAPIImpl(storage, new JsonFormat(), null, true, lock,
                StorageOwnership.BORROWED, SHORT_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void aDistributedUpdateInsideATransactionOnTheSameEntityRidesTheLockAlreadyHeld() {
        UUID player = UUID.randomUUID();
        long start = System.nanoTime();

        api.transaction(player, tx -> {
            api.update(player, COINS, coins -> coins + 5, UpdateMode.DISTRIBUTED);
        });

        assertEquals(5, api.get(player, COINS));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(SHORT_TIMEOUT) < 0,
                "the update must not have waited on a lock this thread already holds");
    }

    @Test
    void aDistributedUpdateOnAnotherEntityInsideATransactionFailsImmediately() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long start = System.nanoTime();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> api.transaction(player, tx -> {
                    api.update(other, COINS, coins -> coins + 1, UpdateMode.DISTRIBUTED);
                }));

        assertTrue(failure.getMessage().contains("player:" + other));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(SHORT_TIMEOUT) < 0,
                "a second entity's lock must be refused outright, not waited for");
    }

    @Test
    void aDistributedLinkedUpdateInsideADirectTransactionRidesTheLockAlreadyHeld() {
        PlayerField<UUID> coopKey = PlayerField.create("mode", "coop_key", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> coop = LinkType.create("mode_coop", Codecs.UUID, coopKey);
        LinkedField<UUID, Integer> balance = LinkedField.create("mode", "balance", Codecs.INT, 0, coop);
        UUID key = UUID.randomUUID();

        api.transactionDirect(key, coop, tx -> {
            api.updateDirect(key, balance, value -> value + 3, UpdateMode.DISTRIBUTED);
            return null;
        });

        assertEquals(3, api.getDirect(key, balance));
    }

    @Test
    void theConfiguredTimeoutBoundsHowLongAWriteWaitsForTheLock() {
        UUID player = UUID.randomUUID();
        try (DistributedLock.Handle held = lock.acquire("player:" + player, Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            assertThrows(LockAcquisitionException.class,
                    () -> api.update(player, COINS, coins -> coins + 1, UpdateMode.DISTRIBUTED));
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(waited.compareTo(SHORT_TIMEOUT) >= 0, "gave up before the configured timeout: " + waited);
            assertTrue(waited.compareTo(Duration.ofSeconds(5)) < 0,
                    "waited far longer than configured, so the timeout is not being used: " + waited);
        }
    }

    @Test
    void theConfiguredTimeoutBoundsHowLongATransactionWaitsForTheLock() {
        UUID player = UUID.randomUUID();
        try (DistributedLock.Handle held = lock.acquire("player:" + player, Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            assertThrows(LockAcquisitionException.class,
                    () -> api.transaction(player, tx -> {
                        tx.set(COINS, 1);
                    }));
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(waited.compareTo(SHORT_TIMEOUT) >= 0, "gave up before the configured timeout: " + waited);
            assertTrue(waited.compareTo(Duration.ofSeconds(5)) < 0,
                    "waited far longer than configured, so the timeout is not being used: " + waited);
        }
    }

    @Test
    void aDistributedUpdateStillTakesTheLockWhenNothingIsHeld() throws Exception {
        UUID player = UUID.randomUUID();
        AtomicBoolean sawContention = new AtomicBoolean();

        try (DistributedLock.Handle held = lock.acquire("player:" + player, Duration.ofSeconds(1))) {
            Thread contender = new Thread(() -> {
                try {
                    api.update(player, COINS, coins -> coins + 1, UpdateMode.DISTRIBUTED);
                } catch (LockAcquisitionException expected) {
                    sawContention.set(true);
                }
            });
            contender.start();
            contender.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertTrue(sawContention.get(), "the write must actually contend for the entity's lock");
    }
}
