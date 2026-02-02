package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.event.LinkChangeListener;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventTest {

    private DataAPIImpl api;

    private static final PlayerField<Integer> COINS = PlayerField.create("test", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("test", "name", Codecs.STRING, "");
    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create("test", "level", Codecs.INT, 1, ISLAND);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new InMemoryDataStorage());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    // ==================== Player Data Listeners ====================

    @Test
    void playerListenerReceivesOldAndNewValues() {
        UUID player = UUID.randomUUID();
        AtomicReference<Integer> oldVal = new AtomicReference<>();
        AtomicReference<Integer> newVal = new AtomicReference<>();

        api.subscribe(COINS, (p, old, nw) -> {
            oldVal.set(old);
            newVal.set(nw);
        });

        api.set(player, COINS, 100);
        assertEquals(0, oldVal.get());
        assertEquals(100, newVal.get());
    }

    @Test
    void playerListenerReceivesCorrectPlayer() {
        UUID player = UUID.randomUUID();
        AtomicReference<UUID> receivedPlayer = new AtomicReference<>();

        api.subscribe(COINS, (p, old, nw) -> receivedPlayer.set(p));

        api.set(player, COINS, 100);
        assertEquals(player, receivedPlayer.get());
    }

    @Test
    void playerListenerFiresOnUpdate() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 50);

        AtomicReference<Integer> oldVal = new AtomicReference<>();
        AtomicReference<Integer> newVal = new AtomicReference<>();

        api.subscribe(COINS, (p, old, nw) -> {
            oldVal.set(old);
            newVal.set(nw);
        });

        api.update(player, COINS, c -> c + 25);
        assertEquals(50, oldVal.get());
        assertEquals(75, newVal.get());
    }

    @Test
    void multipleListenersAllFire() {
        UUID player = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger(0);

        api.subscribe(COINS, (p, old, nw) -> callCount.incrementAndGet());
        api.subscribe(COINS, (p, old, nw) -> callCount.incrementAndGet());
        api.subscribe(COINS, (p, old, nw) -> callCount.incrementAndGet());

        api.set(player, COINS, 100);
        assertEquals(3, callCount.get());
    }

    @Test
    void listenerForOneFieldDoesNotFireForAnother() {
        UUID player = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);

        api.subscribe(NAME, (p, old, nw) -> fired.set(true));

        api.set(player, COINS, 100);
        assertFalse(fired.get());
    }

    @Test
    void listenerFiresForEachSet() {
        UUID player = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger(0);

        api.subscribe(COINS, (p, old, nw) -> callCount.incrementAndGet());

        api.set(player, COINS, 100);
        api.set(player, COINS, 200);
        api.set(player, COINS, 300);

        assertEquals(3, callCount.get());
    }

    // ==================== Linked Data Listeners ====================

    @Test
    void linkedListenerReceivesValues() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicReference<Integer> oldVal = new AtomicReference<>();
        AtomicReference<Integer> newVal = new AtomicReference<>();

        api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> {
            oldVal.set(old);
            newVal.set(nw);
        });

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 5);

        assertEquals(1, oldVal.get());
        assertEquals(5, newVal.get());
    }

    @Test
    void linkedListenerReceivesAffectedPlayers() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicReference<Set<UUID>> affectedRef = new AtomicReference<>();

        api.link(p1, ISLAND, islandId);
        api.link(p2, ISLAND, islandId);

        api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> affectedRef.set(affected));

        api.setDirect(islandId, ISLAND_LEVEL, 10);

        Set<UUID> affected = affectedRef.get();
        assertNotNull(affected);
        assertTrue(affected.contains(p1));
        assertTrue(affected.contains(p2));
    }

    @Test
    void linkedListenerReceivesLinkKey() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicReference<UUID> keyRef = new AtomicReference<>();

        api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> keyRef.set(key));

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 5);

        assertEquals(islandId, keyRef.get());
    }

    // ==================== Link Change Listeners ====================

    @Test
    void linkChangeListenerOnLinked() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<UUID> receivedPlayer = new AtomicReference<>();
        AtomicReference<UUID> receivedKey = new AtomicReference<>();

        api.subscribe(ISLAND, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {
                fired.set(true);
                receivedPlayer.set(p);
                receivedKey.set(key);
            }

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {}
        });

        api.link(player, ISLAND, islandId);

        assertTrue(fired.get());
        assertEquals(player, receivedPlayer.get());
        assertEquals(islandId, receivedKey.get());
    }

    @Test
    void linkChangeListenerOnUnlinked() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<UUID> receivedPreviousKey = new AtomicReference<>();

        api.subscribe(ISLAND, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {}

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {
                fired.set(true);
                receivedPreviousKey.set(previousKey);
            }
        });

        api.link(player, ISLAND, islandId);
        api.unlink(player, ISLAND);

        assertTrue(fired.get());
        assertEquals(islandId, receivedPreviousKey.get());
    }

    @Test
    void linkChangeListenerDoesNotFireForDifferentLinkType() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);

        PlayerField<UUID> guildIdField = PlayerField.create("test", "guild_id", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> guild = LinkType.create("guild", Codecs.UUID, guildIdField);

        api.subscribe(guild, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {
                fired.set(true);
            }

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {}
        });

        api.link(player, ISLAND, islandId);
        assertFalse(fired.get());
    }

    @Test
    void unlinkWithoutPriorLinkDoesNotFireListener() {
        UUID player = UUID.randomUUID();
        AtomicBoolean fired = new AtomicBoolean(false);

        api.subscribe(ISLAND, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {}

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {
                fired.set(true);
            }
        });

        api.unlink(player, ISLAND);
        assertFalse(fired.get());
    }
}
