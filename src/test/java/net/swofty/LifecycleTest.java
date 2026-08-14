package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.DataStorage;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the multi-node cache lifecycle: warming data before use (the primitive a
 * proxy uses to load a player on the target server before sending them), evicting it so a
 * later visit is never stale, and deferring writes until flush/unload.
 */
class LifecycleTest {

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("game", "name", Codecs.STRING, "");

    @Test
    void unloadEvictsSoALaterReadIsNotStale() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        // Two independent nodes over shared storage.
        DataAPIImpl nodeA = new DataAPIImpl(storage);
        DataAPIImpl nodeB = new DataAPIImpl(storage);

        nodeA.set(player, COINS, 100);
        assertEquals(100, nodeA.get(player, COINS)); // now cached on A

        // Player moves to B and earns coins there.
        nodeB.set(player, COINS, 500);

        // A still has the stale cached value until the player is unloaded from A.
        assertEquals(100, nodeA.get(player, COINS), "A holds its cached value");
        nodeA.unload(player);
        assertFalse(nodeA.isLoaded(player));
        assertEquals(500, nodeA.get(player, COINS), "after eviction A reloads the fresh value");

        nodeA.shutdown();
        nodeB.shutdown();
    }

    @Test
    void loadWarmsDataThenUnloadEvicts() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        DataAPIImpl writer = new DataAPIImpl(storage);
        writer.set(player, COINS, 7);
        writer.shutdown();

        // Target node warms the player's data up front, as a proxy would before sending them.
        DataAPIImpl target = new DataAPIImpl(storage);
        assertFalse(target.isLoaded(player));
        target.load(player);
        assertTrue(target.isLoaded(player), "warmed into cache");
        assertEquals(7, target.get(player, COINS));

        target.unload(player);
        assertFalse(target.isLoaded(player), "evicted after use");
        target.shutdown();
    }

    @Test
    void deferredPersistenceHoldsWritesUntilFlush() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        DataAPIImpl node = new DataAPIImpl(storage, new JsonFormat(), null, /* autoPersist */ false);
        node.set(player, COINS, 42);

        // Not yet visible to another node — the write is still buffered.
        DataAPIImpl observer = new DataAPIImpl(storage);
        assertEquals(0, observer.get(player, COINS), "write is deferred, not yet persisted");

        node.flush(player);

        DataAPIImpl observer2 = new DataAPIImpl(storage);
        assertEquals(42, observer2.get(player, COINS), "flush persists the buffered write");

        node.shutdown();
        observer.shutdown();
        observer2.shutdown();
    }

    @Test
    void shutdownFlushesDeferredWrites() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        DataAPIImpl node = new DataAPIImpl(storage, new JsonFormat(), null, false);
        node.set(player, COINS, 13);
        node.shutdown(); // must flush before exiting

        DataAPIImpl observer = new DataAPIImpl(storage);
        assertEquals(13, observer.get(player, COINS));
        observer.shutdown();
    }

    @Test
    void deletingAPlayerRemovesTheDocumentAndReadsFallBackToDefaults() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        DataAPIImpl api = new DataAPIImpl(storage);
        api.set(player, COINS, 900);
        api.set(player, NAME, "swofty");
        assertTrue(storage.exists("players", player.toString()));

        api.deletePlayer(player);

        assertFalse(storage.exists("players", player.toString()), "the document must be gone");
        assertFalse(api.isLoaded(player), "nothing may stay cached for a deleted player");
        assertEquals(0, api.get(player, COINS));
        assertEquals("", api.get(player, NAME));

        DataAPIImpl fresh = new DataAPIImpl(storage);
        assertEquals(0, fresh.get(player, COINS), "and a node that reads them later sees nothing either");
        fresh.shutdown();
        api.shutdown();
    }

    @Test
    void deletingAPlayerEvictsTheCacheSoNothingCanWriteTheDocumentBack() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        DataAPIImpl node = new DataAPIImpl(storage, new JsonFormat(), null, /* autoPersist */ false);
        node.set(player, COINS, 900);
        node.flush(player);
        node.set(player, NAME, "swofty"); // still buffered in the container

        node.deletePlayer(player);

        assertFalse(node.isLoaded(player), "the container goes, buffered write and all");
        node.flush(player);
        assertFalse(storage.exists("players", player.toString()),
                "a cache that survived the delete would flush the old document straight back");

        // A later write starts from nothing rather than merging over what was deleted.
        node.set(player, COINS, 1);
        node.flush(player);

        DataAPIImpl observer = new DataAPIImpl(storage);
        assertEquals(1, observer.get(player, COINS));
        assertEquals("", observer.get(player, NAME), "the deleted fields do not ride back in on that write");
        observer.shutdown();
        node.shutdown();
    }

    @Test
    void deletingAPlayerThatWasNeverSeenIsANoOp() {
        DataStorage storage = new InMemoryDataStorage();
        UUID known = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        DataAPIImpl api = new DataAPIImpl(storage);
        api.set(known, COINS, 5);

        assertDoesNotThrow(() -> api.deletePlayer(stranger));

        assertFalse(storage.exists("players", stranger.toString()));
        assertEquals(5, api.get(known, COINS), "an unrelated player is untouched");
        api.shutdown();
    }
}
