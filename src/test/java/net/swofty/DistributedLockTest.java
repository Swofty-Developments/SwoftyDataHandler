package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.lock.DistributedLock;
import net.swofty.lock.InMemoryDistributedLock;
import net.swofty.lock.LockAcquisitionException;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockTest {

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);

    @Test
    void concurrentTransactionsSerialiseUnderTheLock() throws InterruptedException {
        DistributedLock lock = new InMemoryDistributedLock();
        // Shared lock instance across two "nodes" so they contend as they would over Redis.
        DataAPIImpl node = new DataAPIImpl(new InMemoryDataStorage(), new JsonFormat(), null, true, lock);
        UUID player = UUID.randomUUID();
        node.set(player, COINS, 0);

        int threads = 8, incrementsEach = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < incrementsEach; j++) {
                        node.transaction(player, tx -> {
                            tx.set(COINS, tx.get(COINS) + 1);
                            return null;
                        });
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertEquals(threads * incrementsEach, node.get(player, COINS), "no lost updates under contention");
        node.shutdown();
    }

    @Test
    void heldLockBlocksAnotherAcquirerUntilTimeout() throws InterruptedException {
        DistributedLock lock = new InMemoryDistributedLock();
        AtomicInteger failures = new AtomicInteger();

        DistributedLock.Handle held = lock.acquire("k", Duration.ofSeconds(1));
        Thread other = new Thread(() -> {
            try (DistributedLock.Handle ignored = lock.acquire("k", Duration.ofMillis(100))) {
                // should not get here while held
            } catch (LockAcquisitionException e) {
                failures.incrementAndGet();
            }
        });
        other.start();
        other.join();
        held.close();

        assertEquals(1, failures.get(), "second acquirer times out while the lock is held");
    }
}
