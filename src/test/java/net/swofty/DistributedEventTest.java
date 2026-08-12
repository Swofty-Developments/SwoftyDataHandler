package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.LinkChangeListener;
import net.swofty.event.PubSubHandler;
import net.swofty.lock.DistributedLock;
import net.swofty.lock.InMemoryDistributedLock;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the cross-node paths with two API instances over one shared storage and an
 * in-process pub/sub channel, so the distributed behaviour is covered without a Redis server.
 */
class DistributedEventTest {

    // Hands every published message straight to every subscriber, this node included — the
    // event bus is what filters its own messages out by node id. Delivery can be stopped to
    // model a node that has not heard about a peer's change (yet, or ever).
    private static final class LoopbackChannel {
        private final List<PubSubHandler.MessageHandler> handlers = new CopyOnWriteArrayList<>();
        private volatile boolean delivering = true;

        void dropMessages() {
            delivering = false;
        }

        PubSubHandler handler() {
            return new PubSubHandler() {
                @Override
                public void publish(String message) {
                    if (!delivering) return;
                    for (MessageHandler handler : handlers) {
                        handler.onMessage(message);
                    }
                }

                @Override
                public void subscribe(MessageHandler handler) {
                    handlers.add(handler);
                }

                @Override
                public void shutdown() {}
            };
        }
    }

    private static final PlayerField<Integer> COINS = PlayerField.create("dist", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("dist", "name", Codecs.STRING, "");
    private static final PlayerField<UUID> ISLAND_ID =
            PlayerField.create("dist", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> ISLAND = LinkType.create("dist_island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Integer> ISLAND_LEVEL =
            LinkedField.create("dist", "level", Codecs.INT, 1, ISLAND);
    private static final LinkedField<UUID, Long> ISLAND_BANK =
            LinkedField.create("dist", "bank", Codecs.LONG, 0L, ISLAND);

    private InMemoryDataStorage storage;
    private LoopbackChannel channel;
    private DistributedLock lock;
    private DataAPIImpl nodeA;
    private DataAPIImpl nodeB;

    @BeforeEach
    void setUp() {
        storage = new InMemoryDataStorage();
        channel = new LoopbackChannel();
        lock = new InMemoryDistributedLock();
        nodeA = new DataAPIImpl(storage, channel.handler());
        nodeB = new DataAPIImpl(storage, channel.handler());
    }

    @AfterEach
    void tearDown() {
        nodeA.shutdown();
        nodeB.shutdown();
    }

    private DataAPIImpl freshNode() {
        return new DataAPIImpl(storage);
    }

    // A node sharing the one cross-node lock; pass null to cut it off from pub/sub entirely.
    private DataAPIImpl lockedNode(PubSubHandler pubSub) {
        return new DataAPIImpl(storage, new JsonFormat(), pubSub, true, lock);
    }

    @Test
    void remoteLinkedListenerReceivesTypedLinkKey() {
        UUID islandId = UUID.randomUUID();
        AtomicReference<Object> receivedKey = new AtomicReference<>();
        AtomicReference<Integer> receivedValue = new AtomicReference<>();

        nodeB.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> {
            receivedKey.set(key);
            receivedValue.set(nw);
        });

        nodeA.setDirect(islandId, ISLAND_LEVEL, 5);

        assertInstanceOf(UUID.class, receivedKey.get());
        assertEquals(islandId, receivedKey.get());
        assertEquals(5, receivedValue.get());
    }

    @Test
    void nullValuesPublishAndArriveAsNull() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        List<UUID[]> received = new CopyOnWriteArrayList<>();

        nodeB.subscribe(ISLAND_ID, (p, old, nw) -> received.add(new UUID[]{old, nw}));

        assertDoesNotThrow(() -> nodeA.set(player, ISLAND_ID, islandId));
        assertDoesNotThrow(() -> nodeA.set(player, ISLAND_ID, null));

        assertEquals(2, received.size());
        assertNull(received.get(0)[0]);
        assertEquals(islandId, received.get(0)[1]);
        assertEquals(islandId, received.get(1)[0]);
        assertNull(received.get(1)[1]);
    }

    @Test
    void remoteChangeAndLocalWriteBothReachStorage() {
        UUID player = UUID.randomUUID();

        nodeB.subscribe(COINS, (p, old, nw) -> {});
        nodeB.load(player);

        nodeA.set(player, COINS, 100);
        nodeB.set(player, NAME, "bob");

        assertEquals(100, nodeB.get(player, COINS));

        DataAPIImpl fresh = freshNode();
        try {
            assertEquals(100, fresh.get(player, COINS));
            assertEquals("bob", fresh.get(player, NAME));
        } finally {
            fresh.shutdown();
        }
    }

    @Test
    void transactionalWritesFireLocalAndRemoteEvents() {
        UUID player = UUID.randomUUID();
        AtomicReference<Integer> localValue = new AtomicReference<>();
        AtomicReference<Integer> remoteValue = new AtomicReference<>();
        AtomicReference<Integer> remoteOldValue = new AtomicReference<>();

        nodeA.subscribe(COINS, (p, old, nw) -> localValue.set(nw));
        nodeB.subscribe(COINS, (p, old, nw) -> {
            remoteOldValue.set(old);
            remoteValue.set(nw);
        });
        nodeB.load(player);

        nodeA.transaction(player, tx -> {
            tx.set(COINS, 250);
        });

        assertEquals(250, localValue.get());
        assertEquals(250, remoteValue.get());
        assertEquals(0, remoteOldValue.get());
        assertEquals(250, nodeB.get(player, COINS));
    }

    @Test
    void transactionDirectPersistsAndPublishes() {
        UUID islandId = UUID.randomUUID();
        AtomicReference<Object> remoteKey = new AtomicReference<>();
        AtomicReference<Long> remoteValue = new AtomicReference<>();

        nodeB.subscribe(ISLAND_BANK, (key, old, nw, affected) -> {
            remoteKey.set(key);
            remoteValue.set(nw);
        });

        nodeA.setDirect(islandId, ISLAND_BANK, 5000L);

        Long balance = nodeA.transactionDirect(islandId, ISLAND, tx -> {
            long updated = tx.get(ISLAND_BANK) - 1000L;
            tx.set(ISLAND_BANK, updated);
            return updated;
        });

        assertEquals(4000L, balance);
        assertEquals(4000L, nodeA.getDirect(islandId, ISLAND_BANK));
        assertEquals(islandId, remoteKey.get());
        assertEquals(4000L, remoteValue.get());

        DataAPIImpl fresh = freshNode();
        try {
            assertEquals(4000L, fresh.getDirect(islandId, ISLAND_BANK));
        } finally {
            fresh.shutdown();
        }
    }

    @Test
    void transactionDirectAbortLeavesStoredValueAlone() {
        UUID islandId = UUID.randomUUID();
        nodeA.setDirect(islandId, ISLAND_BANK, 5000L);

        Long balance = nodeA.transactionDirect(islandId, ISLAND, tx -> {
            tx.set(ISLAND_BANK, 1L);
            tx.abort();
            return 1L;
        });

        assertNull(balance);
        assertEquals(5000L, nodeA.getDirect(islandId, ISLAND_BANK));

        DataAPIImpl fresh = freshNode();
        try {
            assertEquals(5000L, fresh.getDirect(islandId, ISLAND_BANK));
        } finally {
            fresh.shutdown();
        }
    }

    @Test
    void freshNodeRecoversLinkFromStorage() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        nodeA.link(player, ISLAND, islandId);
        nodeA.setDirect(islandId, ISLAND_LEVEL, 7);

        DataAPIImpl fresh = freshNode();
        try {
            assertEquals(Optional.of(islandId), fresh.getLinkKey(player, ISLAND));
            assertEquals(7, fresh.get(player, ISLAND_LEVEL));
            fresh.set(player, ISLAND_LEVEL, 9);
        } finally {
            fresh.shutdown();
        }

        DataAPIImpl reread = freshNode();
        try {
            assertEquals(9, reread.getDirect(islandId, ISLAND_LEVEL));
        } finally {
            reread.shutdown();
        }
    }

    @Test
    void transactionDirectReadsPastAStaleCache() {
        UUID islandId = UUID.randomUUID();
        DataAPIImpl a = lockedNode(channel.handler());
        DataAPIImpl b = lockedNode(channel.handler());
        try {
            a.setDirect(islandId, ISLAND_BANK, 1000L);
            b.loadLink(ISLAND, islandId);
            assertEquals(1000L, b.getDirect(islandId, ISLAND_BANK));

            // B never hears about the next write, so its cached 1000 is now stale.
            channel.dropMessages();
            a.setDirect(islandId, ISLAND_BANK, 7000L);

            Long seenInsideTransaction = b.transactionDirect(islandId, ISLAND, tx -> {
                long bank = tx.get(ISLAND_BANK);
                tx.set(ISLAND_BANK, bank + 1L);
                return bank;
            });

            assertEquals(7000L, seenInsideTransaction);

            DataAPIImpl fresh = freshNode();
            try {
                assertEquals(7001L, fresh.getDirect(islandId, ISLAND_BANK));
            } finally {
                fresh.shutdown();
            }
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void playerTransactionReadsPastAStaleCache() {
        UUID player = UUID.randomUUID();
        DataAPIImpl a = lockedNode(channel.handler());
        DataAPIImpl b = lockedNode(channel.handler());
        try {
            a.set(player, COINS, 100);
            b.load(player);
            assertEquals(100, b.get(player, COINS));

            channel.dropMessages();
            a.set(player, COINS, 900);

            Integer seenInsideTransaction = b.transaction(player, tx -> {
                int coins = tx.get(COINS);
                tx.set(COINS, coins + 1);
                return coins;
            });

            assertEquals(900, seenInsideTransaction);

            DataAPIImpl fresh = freshNode();
            try {
                assertEquals(901, fresh.get(player, COINS));
            } finally {
                fresh.shutdown();
            }
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void concurrentDirectTransactionsDoNotLoseUpdates() throws InterruptedException {
        int rounds = 100;
        UUID islandId = UUID.randomUUID();
        // No pub/sub at all: the cross-node lock and the refresh it enables are the only things
        // keeping the two nodes from overwriting each other's increments.
        DataAPIImpl a = lockedNode(null);
        DataAPIImpl b = lockedNode(null);
        try {
            a.setDirect(islandId, ISLAND_BANK, 0L);

            Thread first = incrementing(a, islandId, rounds);
            Thread second = incrementing(b, islandId, rounds);
            first.start();
            second.start();
            first.join(30_000);
            second.join(30_000);
            assertFalse(first.isAlive(), "increment thread did not finish");
            assertFalse(second.isAlive(), "increment thread did not finish");

            DataAPIImpl fresh = freshNode();
            try {
                assertEquals(2L * rounds, fresh.getDirect(islandId, ISLAND_BANK));
            } finally {
                fresh.shutdown();
            }
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    private Thread incrementing(DataAPIImpl node, UUID islandId, int rounds) {
        return new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                node.transactionDirect(islandId, ISLAND, tx -> {
                    tx.set(ISLAND_BANK, tx.get(ISLAND_BANK) + 1L);
                    return null;
                });
            }
        });
    }

    @Test
    void linkStateConvergesOnOtherNodes() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicReference<Object> linkedKey = new AtomicReference<>();

        nodeB.subscribe(ISLAND, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {
                linkedKey.set(key);
            }

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {}
        });

        nodeA.link(player, ISLAND, islandId);

        assertInstanceOf(UUID.class, linkedKey.get());
        assertEquals(islandId, linkedKey.get());
        assertEquals(Optional.of(islandId), nodeB.getLinkKey(player, ISLAND));

        nodeA.setDirect(islandId, ISLAND_LEVEL, 3);
        assertEquals(3, nodeB.get(player, ISLAND_LEVEL));
    }
}
