package net.swofty.api;

import net.swofty.PlayerField;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.InMemoryDataStorage;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A node that has been up for a week has seen every player who ever logged in. Anything kept per
 * entity has to either be evicted or be bounded by construction, or the process leaks for as long
 * as it runs.
 */
class EntityStateTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("state", "coins", Codecs.INT, 0);

    @Test
    void loadAndUnloadCyclesLeaveNothingBehind() {
        PlayerDataManager players = new PlayerDataManager(
                new InMemoryDataStorage(), new JsonFormat(), new EventBus(), true);

        for (int i = 0; i < 500; i++) {
            UUID player = UUID.randomUUID();
            players.load(player);
            players.set(player, COINS, i);
            players.unload(player);
        }

        assertEquals(0, players.cachedCount());
        assertEquals(0, players.loadsInFlight());
    }

    @Test
    void asyncLoadCyclesLeaveNoInFlightState() throws Exception {
        PlayerDataManager players = new PlayerDataManager(
                new InMemoryDataStorage(), new JsonFormat(), new EventBus(), true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 200; i++) {
                UUID player = UUID.randomUUID();
                players.loadAsync(player, executor).join();
                players.unload(player);
            }
            assertEquals(0, players.loadsInFlight());
            assertEquals(0, players.cachedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anEntitysMonitorKeepsItsIdentityAcrossAnUnload() {
        PlayerDataManager players = new PlayerDataManager(
                new InMemoryDataStorage(), new JsonFormat(), new EventBus(), true);
        UUID player = UUID.randomUUID();

        Object before = players.getLock(player);
        players.load(player);
        players.unload(player);

        // Evicting a monitor is worse than leaking one: a thread that read the old monitor just
        // before it was dropped would be excluding nobody at all.
        assertSame(before, players.getLock(player));
    }

    @Test
    void linkedEntityCyclesLeaveNothingBehind() {
        LinkRegistryImpl registry = new LinkRegistryImpl();
        LinkedDataManager linked = new LinkedDataManager(
                new InMemoryDataStorage(), new JsonFormat(), new EventBus(), registry, true);

        for (int i = 0; i < 500; i++) {
            String key = UUID.randomUUID().toString();
            linked.loadLinked("coop", key);
            linked.unloadLinked("coop", key);
        }

        assertEquals(0, linked.cachedCount());
    }
}
