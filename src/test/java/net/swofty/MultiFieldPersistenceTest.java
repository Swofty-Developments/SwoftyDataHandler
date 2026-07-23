package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.storage.DataStorage;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the "partial write drops untouched fields" bug. A node that
 * writes one field without ever having read another must not wipe the unread field
 * from storage — otherwise moving a player between server instances loses data.
 */
class MultiFieldPersistenceTest {

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);
    private static final PlayerField<Integer> GEMS = PlayerField.create("game", "gems", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("game", "name", Codecs.STRING, "");

    @Test
    void writingOneFieldDoesNotDropUntouchedFields() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        // Session 1: populate several fields.
        DataAPIImpl api1 = new DataAPIImpl(storage);
        api1.set(player, COINS, 100);
        api1.set(player, GEMS, 50);
        api1.set(player, NAME, "swofty");
        api1.shutdown();

        // Session 2 (fresh node, empty cache): touch ONLY coins, never read gems/name.
        DataAPIImpl api2 = new DataAPIImpl(storage);
        api2.set(player, COINS, 999);
        api2.shutdown();

        // Session 3: the untouched fields must survive.
        DataAPIImpl api3 = new DataAPIImpl(storage);
        assertEquals(999, api3.get(player, COINS), "updated field");
        assertEquals(50, api3.get(player, GEMS), "untouched field must not be dropped");
        assertEquals("swofty", api3.get(player, NAME), "untouched field must not be dropped");
        api3.shutdown();
    }

    @Test
    void nullSetDeletesFromBackingDocumentInsteadOfResurrecting() {
        DataStorage storage = new InMemoryDataStorage();
        UUID player = UUID.randomUUID();

        // A value exists in the backing document.
        DataAPIImpl api1 = new DataAPIImpl(storage);
        api1.set(player, COINS, 7);
        api1.set(player, GEMS, 5);
        api1.shutdown();

        // A fresh node clears coins. The merge must honour the deletion (tombstone)
        // rather than resurrecting the old value from the backing document.
        DataAPIImpl api2 = new DataAPIImpl(storage);
        api2.set(player, COINS, null);
        api2.shutdown();

        DataAPIImpl api3 = new DataAPIImpl(storage);
        assertEquals(0, api3.get(player, COINS), "cleared field falls back to default, not the old value");
        assertEquals(5, api3.get(player, GEMS), "sibling field survives the clear");
        api3.shutdown();
    }
}
