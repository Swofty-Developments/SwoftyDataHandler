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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unlinking the last member of a shared entity leaves the entity itself in storage, which is
 * correct — members come and go — but it means a disbanded coop is a document nobody will ever read
 * or delete again. {@code deleteLink} is the operation that ends one.
 */
class DeleteLinkTest {

    private static final class LoopbackChannel {
        private final List<PubSubHandler.MessageHandler> handlers = new CopyOnWriteArrayList<>();

        PubSubHandler handler() {
            return new PubSubHandler() {
                @Override public void publish(String message) {
                    for (MessageHandler handler : handlers) handler.onMessage(message);
                }
                @Override public void subscribe(MessageHandler handler) { handlers.add(handler); }
                @Override public void shutdown() {}
            };
        }
    }

    private static final PlayerField<UUID> COOP_KEY =
            PlayerField.create("del", "coop_key", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> COOP = LinkType.create("del_coop", Codecs.UUID, COOP_KEY);
    private static final LinkedField<UUID, Integer> BALANCE =
            LinkedField.create("del", "balance", Codecs.INT, 0, COOP);

    private InMemoryDataStorage storage;
    private LoopbackChannel channel;
    private DataAPI nodeA;
    private DataAPI nodeB;

    @BeforeEach
    void setUp() {
        storage = new InMemoryDataStorage();
        channel = new LoopbackChannel();
        nodeA = new DataAPIImpl(storage, new JsonFormat(), channel.handler());
        nodeB = new DataAPIImpl(storage, new JsonFormat(), channel.handler());
    }

    @AfterEach
    void tearDown() {
        nodeA.shutdown();
        nodeB.shutdown();
    }

    @Test
    void deletingALinkRemovesTheDocumentAndUnlinksEveryoneEverywhere() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        List<UUID> unlinkedOnPeer = new ArrayList<>();

        nodeB.subscribe(COOP, new LinkChangeListener<UUID>() {
            @Override public void onLinked(UUID player, LinkType<UUID> type, UUID key) {}
            @Override public void onUnlinked(UUID player, LinkType<UUID> type, UUID key) { unlinkedOnPeer.add(player); }
        });

        nodeA.link(first, COOP, coop);
        nodeA.link(second, COOP, coop);
        nodeA.setDirect(coop, BALANCE, 250);
        nodeB.loadLink(COOP, coop);
        assertTrue(storage.exists("linked/del_coop", coop.toString()));
        assertTrue(nodeB.isLinkLoaded(COOP, coop));

        nodeA.deleteLink(COOP, coop);

        assertFalse(storage.exists("linked/del_coop", coop.toString()), "the shared document must be gone");
        assertEquals(Optional.empty(), nodeA.getLinkKey(first, COOP));
        assertEquals(Optional.empty(), nodeA.getLinkKey(second, COOP));
        assertEquals(Optional.empty(), nodeB.getLinkKey(first, COOP));
        assertEquals(Optional.empty(), nodeB.getLinkKey(second, COOP));
        assertFalse(nodeB.isLinkLoaded(COOP, coop), "the peer must drop its cached container");
        assertEquals(Set.of(first, second), Set.copyOf(unlinkedOnPeer));
        assertEquals(0, nodeA.getDirect(coop, BALANCE), "a deleted entity reads as its defaults");
        assertEquals(0, nodeB.getDirect(coop, BALANCE));
    }

    @Test
    void aDeletedLinkStaysDeletedForANodeThatReadsItLater() {
        UUID player = UUID.randomUUID();
        UUID coop = UUID.randomUUID();

        nodeA.link(player, COOP, coop);
        nodeA.setDirect(coop, BALANCE, 99);
        nodeA.deleteLink(COOP, coop);

        try (DataAPI fresh = new DataAPIImpl(storage)) {
            assertEquals(Optional.empty(), fresh.getLinkKey(player, COOP));
            assertEquals(0, fresh.getDirect(coop, BALANCE));
        }
    }

    @Test
    void deletingAPlayerUnlinksThemAndLeavesTheSharedEntityAlone() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        List<UUID> unlinkedOnPeer = new CopyOnWriteArrayList<>();
        List<Set<UUID>> membersSeen = new CopyOnWriteArrayList<>();

        nodeB.subscribe(COOP, new LinkChangeListener<UUID>() {
            @Override public void onLinked(UUID player, LinkType<UUID> type, UUID key) {}
            @Override public void onUnlinked(UUID player, LinkType<UUID> type, UUID key) { unlinkedOnPeer.add(player); }
        });
        nodeA.subscribe(BALANCE, (key, old, updated, members) -> membersSeen.add(Set.copyOf(members)));

        nodeA.link(first, COOP, coop);
        nodeA.link(second, COOP, coop);
        nodeA.setDirect(coop, BALANCE, 250);

        nodeA.deletePlayer(first);

        assertTrue(storage.exists("linked/del_coop", coop.toString()), "the shared entity outlives its member");
        assertEquals(250, nodeA.getDirect(coop, BALANCE), "and keeps its data");
        assertEquals(Optional.empty(), nodeA.getLinkKey(first, COOP));
        assertEquals(Optional.empty(), nodeB.getLinkKey(first, COOP), "the peer stops resolving the link too");
        assertEquals(Optional.of(coop), nodeA.getLinkKey(second, COOP), "the surviving member keeps its link");
        assertEquals(List.of(first), unlinkedOnPeer);

        membersSeen.clear();
        nodeA.setDirect(coop, BALANCE, 300);
        assertEquals(List.of(Set.of(second)), membersSeen, "the deleted id is gone from the member set");
    }

    @Test
    void deletingALinkNobodyIsLinkedToStillRemovesTheDocument() {
        UUID coop = UUID.randomUUID();
        nodeA.setDirect(coop, BALANCE, 5);
        assertTrue(storage.exists("linked/del_coop", coop.toString()));

        nodeA.deleteLink(COOP, coop);

        assertFalse(storage.exists("linked/del_coop", coop.toString()));
    }
}
