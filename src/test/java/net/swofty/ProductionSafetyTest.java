package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.PubSubHandler;
import net.swofty.lock.InMemoryDistributedLock;
import net.swofty.storage.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSafetyTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("safe", "coins", Codecs.INT, 0);

    @Test
    void concurrentAsyncLoadsShareOneOperationAndUnloadRunsAfterIt() throws Exception {
        class BlockingStorage extends InMemoryDataStorage {
            final AtomicInteger loads = new AtomicInteger();
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);

            @Override public VersionedData loadVersioned(String type, String id) {
                loads.incrementAndGet();
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.loadVersioned(type, id);
            }
        }

        BlockingStorage storage = new BlockingStorage();
        DataAPI api = new DataAPIImpl(storage);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        UUID player = UUID.randomUUID();
        try {
            CompletableFuture<Void> first = api.loadAsync(player, executor);
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> second = api.loadAsync(player, executor);
            assertSame(first, second);
            CompletionStage<SaveResult> unload = api.unloadAsync(player, executor);
            storage.release.countDown();
            unload.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, storage.loads.get());
            assertFalse(api.isLoaded(player));
        } finally {
            storage.release.countDown();
            api.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void saveResultsAndSnapshotsExposeConfirmedVersions() {
        InMemoryDataStorage storage = new InMemoryDataStorage();
        DataAPI api = new DataAPIImpl(storage);
        UUID player = UUID.randomUUID();
        try {
            SaveResult first = api.set(player, COINS, 1).toCompletableFuture().join();
            SaveResult second = api.update(player, COINS, value -> value + 1).toCompletableFuture().join();
            assertTrue(first.saved());
            assertTrue(second.version() > first.version());

            StorageSnapshot snapshot = api.snapshot();
            BatchSaveResult batch = api.saveSnapshot(snapshot).toCompletableFuture().join();
            assertEquals(snapshot.documents().size(), batch.savedCount());
        } finally {
            api.shutdown();
        }
    }

    @Test
    void staleRemoteEventCannotOverwriteNewerCacheState() {
        class DelayedChannel {
            final List<String> messages = new ArrayList<>();
            final List<PubSubHandler.MessageHandler> handlers = new CopyOnWriteArrayList<>();
            PubSubHandler endpoint() {
                return new PubSubHandler() {
                    @Override public void publish(String message) { messages.add(message); }
                    @Override public void subscribe(MessageHandler handler) { handlers.add(handler); }
                    @Override public void shutdown() {}
                };
            }
            void deliver(String message) { handlers.forEach(handler -> handler.onMessage(message)); }
        }

        InMemoryDataStorage storage = new InMemoryDataStorage();
        DelayedChannel channel = new DelayedChannel();
        DataAPI nodeA = new DataAPIImpl(storage, channel.endpoint());
        DataAPI nodeB = new DataAPIImpl(storage, channel.endpoint());
        UUID player = UUID.randomUUID();
        try {
            nodeB.subscribe(COINS, (ignored, oldValue, newValue) -> {});
            nodeB.load(player);
            nodeA.set(player, COINS, 1);
            nodeA.set(player, COINS, 2);

            channel.deliver(channel.messages.get(1));
            channel.deliver(channel.messages.get(0));
            assertEquals(2, nodeB.get(player, COINS));
        } finally {
            nodeA.shutdown();
            nodeB.shutdown();
        }
    }

    @Test
    void deferredFlushPublishesVersionedSnapshotInvalidation() {
        class ImmediateChannel {
            final List<PubSubHandler.MessageHandler> handlers = new CopyOnWriteArrayList<>();
            PubSubHandler endpoint() { return new PubSubHandler() {
                @Override public void publish(String message) { handlers.forEach(h -> h.onMessage(message)); }
                @Override public void subscribe(MessageHandler handler) { handlers.add(handler); }
                @Override public void shutdown() {}
            }; }
        }
        InMemoryDataStorage storage = new InMemoryDataStorage();
        ImmediateChannel channel = new ImmediateChannel();
        DataAPI writer = new DataAPIImpl(storage, new JsonFormat(), channel.endpoint(), false);
        DataAPI reader = new DataAPIImpl(storage, channel.endpoint());
        UUID player = UUID.randomUUID();
        try {
            reader.load(player);
            writer.set(player, COINS, 9);
            assertEquals(0, reader.get(player, COINS));
            writer.flush(player);
            assertEquals(9, reader.get(player, COINS));
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void distributedOrdinaryUpdatesDoNotLoseWrites() throws Exception {
        InMemoryDataStorage storage = new InMemoryDataStorage();
        InMemoryDistributedLock lock = new InMemoryDistributedLock();
        DataAPI a = new DataAPIImpl(storage, new JsonFormat(), null, true, lock);
        DataAPI b = new DataAPIImpl(storage, new JsonFormat(), null, true, lock);
        UUID player = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            a.set(player, COINS, 0);
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 100; i++) a.update(player, COINS, value -> value + 1, UpdateMode.DISTRIBUTED);
            }, executor);
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 100; i++) b.update(player, COINS, value -> value + 1, UpdateMode.DISTRIBUTED);
            }, executor);
            CompletableFuture.allOf(one, two).get(10, TimeUnit.SECONDS);
            DataAPI fresh = new DataAPIImpl(storage);
            try { assertEquals(200, fresh.get(player, COINS)); }
            finally { fresh.shutdown(); }
        } finally {
            a.shutdown();
            b.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void factoriesReturnFreshDefaultsAndOwnedStorageCloses() {
        PlayerField<List<String>> field = PlayerField.<List<String>>builder(FieldKey.of("safe", "list"))
                .codec(Codecs.list(Codecs.STRING)).defaultFactory(ArrayList::new).build();
        assertNotSame(field.defaultValue(), field.defaultValue());

        AtomicBoolean closed = new AtomicBoolean();
        InMemoryDataStorage storage = new InMemoryDataStorage() {
            @Override public void close() { closed.set(true); }
        };
        new DataAPIImpl(storage, StorageOwnership.OWNED).shutdown();
        assertTrue(closed.get());
    }
}
