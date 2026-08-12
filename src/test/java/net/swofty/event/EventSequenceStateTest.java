package net.swofty.event;

import net.swofty.PlayerField;
import net.swofty.codec.Codecs;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event bus remembers, per entity and field, the newest version it has published or seen, so it
 * can drop an event that arrives after a newer one. Every entity written anywhere in the cluster
 * shows up here, including entities this node never caches, so that memory has to be reclaimable.
 */
class EventSequenceStateTest {
    private static final PlayerField<Integer> COINS = PlayerField.create("seq", "coins", Codecs.INT, 0);

    private static PubSubHandler silent() {
        return new PubSubHandler() {
            @Override public void publish(String message) {}
            @Override public void subscribe(MessageHandler handler) {}
            @Override public void shutdown() {}
        };
    }

    @Test
    void unloadingAnEntityDropsTheOrderingStateKeptForIt() {
        DistributedEventBus bus = new DistributedEventBus(silent());
        UUID[] players = new UUID[100];
        for (int i = 0; i < players.length; i++) {
            players[i] = UUID.randomUUID();
            bus.firePlayerDataChanged(COINS, players[i], 0, i, i + 1);
        }
        assertEquals(players.length, bus.trackedEntities());

        for (UUID player : players) {
            bus.forgetPlayer(player);
        }
        assertEquals(0, bus.trackedEntities());
    }

    @Test
    void orderingStateStaysBoundedForANodeThatNeverUnloads() {
        DistributedEventBus bus = new DistributedEventBus(silent());
        for (int i = 0; i < 20_000; i++) {
            bus.firePlayerDataChanged(COINS, UUID.randomUUID(), 0, i, i + 1);
        }
        assertTrue(bus.trackedEntities() <= 4096,
                "ordering state must be capped, tracked " + bus.trackedEntities() + " entities");
    }

    @Test
    void aLinkedEntityDropsItsOrderingStateWhenUnloaded() {
        DistributedEventBus bus = new DistributedEventBus(silent());
        bus.fireLinkedSnapshotSaved("coop", "abc", 3L);
        assertEquals(1, bus.trackedEntities());

        bus.forgetLinked("coop", "abc");
        assertEquals(0, bus.trackedEntities());
    }
}
