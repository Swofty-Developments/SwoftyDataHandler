package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.LinkChangeListener;
import net.swofty.event.PubSubHandler;
import net.swofty.storage.InMemoryDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a peer node ends up believing after another node writes. The interesting cases are the ones
 * where a change is not a single immediate field write: a transaction (several fields, one
 * document version) and a node that defers its writes (no document version at all).
 */
class DistributedConvergenceTest {

    private static final class LoopbackChannel {
        private final List<PubSubHandler.MessageHandler> handlers = new CopyOnWriteArrayList<>();
        private final List<String> published = new CopyOnWriteArrayList<>();
        private volatile boolean delivering = true;
        private volatile boolean recording;

        void deliver(boolean enabled) {
            delivering = enabled;
        }

        void record(boolean enabled) {
            recording = enabled;
        }

        String lastPublished() {
            return published.get(published.size() - 1);
        }

        void replay(String message) {
            for (PubSubHandler.MessageHandler handler : handlers) handler.onMessage(message);
        }

        PubSubHandler handler() {
            return new PubSubHandler() {
                @Override public void publish(String message) {
                    if (recording) published.add(message);
                    if (!delivering) return;
                    for (MessageHandler handler : handlers) handler.onMessage(message);
                }
                @Override public void subscribe(MessageHandler handler) { handlers.add(handler); }
                @Override public void shutdown() {}
            };
        }
    }

    private static final PlayerField<Integer> COINS = PlayerField.create("conv", "coins", Codecs.INT, 0);
    private static final PlayerField<Integer> GEMS = PlayerField.create("conv", "gems", Codecs.INT, 0);
    private static final PlayerField<String> TITLE = PlayerField.create("conv", "title", Codecs.STRING, "");
    private static final PlayerField<UUID> COOP_KEY =
            PlayerField.create("conv", "coop_key", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> COOP = LinkType.create("conv_coop", Codecs.UUID, COOP_KEY);
    private static final LinkedField<UUID, Integer> COOP_BALANCE =
            LinkedField.create("conv", "balance", Codecs.INT, 0, COOP);

    private InMemoryDataStorage storage;
    private LoopbackChannel channel;

    @BeforeEach
    void setUp() {
        storage = new InMemoryDataStorage();
        channel = new LoopbackChannel();
    }

    private DataAPI node(boolean autoPersist) {
        return new DataAPIImpl(storage, new JsonFormat(), channel.handler(), autoPersist);
    }

    @AfterEach
    void tearDown() {
        channel.deliver(true);
    }

    @Test
    void everyFieldOfATransactionReachesAPeersListenersAndCache() {
        DataAPI writer = node(true);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        try {
            reader.subscribe(COINS, (id, oldValue, newValue) -> seen.add("coins=" + newValue));
            reader.subscribe(GEMS, (id, oldValue, newValue) -> seen.add("gems=" + newValue));
            reader.subscribe(TITLE, (id, oldValue, newValue) -> seen.add("title=" + newValue));
            reader.load(player);

            writer.transaction(player, tx -> {
                tx.set(COINS, 10);
                tx.set(GEMS, 5);
                tx.set(TITLE, "champion");
            });

            // One commit is one document version, so a guard that ordered events per document
            // would drop every field of the transaction but the first.
            assertEquals(3, seen.size(), "expected all three transactional fields, got " + seen);
            assertTrue(seen.contains("coins=10"));
            assertTrue(seen.contains("gems=5"));
            assertTrue(seen.contains("title=champion"));

            assertEquals(10, reader.get(player, COINS));
            assertEquals(5, reader.get(player, GEMS));
            assertEquals("champion", reader.get(player, TITLE));
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void linkAndUnlinkConvergeOnPeersEvenWhenWritesAreDeferred() {
        DataAPI writer = node(false);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        try {
            reader.subscribe(COOP, new LinkChangeListener<UUID>() {
                @Override public void onLinked(UUID id, LinkType<UUID> type, UUID key) { seen.add("linked"); }
                @Override public void onUnlinked(UUID id, LinkType<UUID> type, UUID key) { seen.add("unlinked"); }
            });

            // Link state is registry state, not a document write: deferring writes must not stop a
            // peer from resolving the link.
            writer.link(player, COOP, coop);
            assertEquals(Optional.of(coop), reader.getLinkKey(player, COOP));

            writer.unlink(player, COOP);
            assertEquals(Optional.empty(), reader.getLinkKey(player, COOP));
            assertEquals(List.of("linked", "unlinked"), seen);
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void deferredLinkedWritesConvergeOnPeers() {
        DataAPI writer = node(false);
        DataAPI reader = node(true);
        UUID coop = UUID.randomUUID();
        try {
            reader.subscribe(COOP_BALANCE, (key, oldValue, newValue, affected) -> {});
            reader.loadLink(COOP, coop);

            writer.setDirect(coop, COOP_BALANCE, 42);
            assertEquals(42, reader.getDirect(coop, COOP_BALANCE));
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void aFlushedSnapshotReconcilesAPeersLinkRegistry() {
        DataAPI writer = node(false);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        try {
            reader.subscribe(COOP, new LinkChangeListener<UUID>() {
                @Override public void onLinked(UUID id, LinkType<UUID> type, UUID key) {}
                @Override public void onUnlinked(UUID id, LinkType<UUID> type, UUID key) {}
            });
            writer.link(player, COOP, coop);
            reader.load(player);
            assertEquals(Optional.of(coop), reader.getLinkKey(player, COOP));

            // The peer misses the unlink entirely and only ever sees the flush, which replaces the
            // whole document. Links live on that document, so the registry has to follow it.
            channel.deliver(false);
            writer.unlink(player, COOP);
            channel.deliver(true);
            writer.flush(player);

            assertEquals(Optional.empty(), reader.getLinkKey(player, COOP));
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void aReplayedEventCannotRevertAnEntityThisNodeIsStillServing() {
        DataAPI writer = node(true);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        try {
            reader.subscribe(COINS, (id, oldValue, newValue) -> {});
            reader.load(player);

            channel.record(true);
            writer.set(player, COINS, 10);
            String olderEvent = channel.lastPublished();
            writer.set(player, COINS, 20);
            channel.record(false);
            assertEquals(20, reader.get(player, COINS));

            // Enough traffic about entities this node has never heard of to overrun any cap on the
            // ordering state. The player is loaded here and being served right now, so their entry
            // is the one thing that must not be dropped to make room.
            for (int i = 0; i < 6_000; i++) {
                writer.set(UUID.randomUUID(), COINS, i);
            }

            channel.replay(olderEvent);
            assertEquals(20, reader.get(player, COINS),
                    "a replayed older event reverted a player this node is serving");
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void loadingAnEntityRejectsEventsOlderThanTheDocumentItJustRead() {
        DataAPI writer = node(true);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        try {
            reader.subscribe(COINS, (id, oldValue, newValue) -> {});

            channel.record(true);
            writer.set(player, COINS, 10);
            String olderEvent = channel.lastPublished();
            writer.set(player, COINS, 20);
            channel.record(false);

            // The reader is not serving this player, so its ordering state is fair game for the cap
            // and unrelated traffic drops it. Loading the player afterwards has to re-establish the
            // floor from the document itself, or a replayed event walks straight back in.
            for (int i = 0; i < 6_000; i++) {
                writer.set(UUID.randomUUID(), COINS, i);
            }
            reader.load(player);
            assertEquals(20, reader.get(player, COINS));

            channel.replay(olderEvent);
            assertEquals(20, reader.get(player, COINS),
                    "an event older than the document this node just read was applied");
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void anOlderEventForOneFieldNeverOverwritesANewerOneForAnother() {
        DataAPI writer = node(true);
        DataAPI reader = node(true);
        UUID player = UUID.randomUUID();
        try {
            reader.subscribe(COINS, (id, oldValue, newValue) -> {});
            reader.subscribe(GEMS, (id, oldValue, newValue) -> {});
            reader.load(player);

            writer.set(player, COINS, 1);
            writer.set(player, GEMS, 2);
            writer.set(player, COINS, 3);

            assertEquals(3, reader.get(player, COINS));
            assertEquals(2, reader.get(player, GEMS));
        } finally {
            writer.shutdown();
            reader.shutdown();
        }
    }
}
