package net.swofty;

import net.swofty.storage.InMemoryDataStorage;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDataStorageTest {

    private InMemoryDataStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryDataStorage();
    }

    @Test
    void saveAndLoad() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        storage.save("players", "abc", data);
        assertArrayEquals(data, storage.load("players", "abc"));
    }

    @Test
    void loadNonExistentReturnsNull() {
        assertNull(storage.load("players", "missing"));
    }

    @Test
    void existsReturnsTrueAfterSave() {
        storage.save("players", "abc", new byte[]{1});
        assertTrue(storage.exists("players", "abc"));
    }

    @Test
    void existsReturnsFalseForMissing() {
        assertFalse(storage.exists("players", "abc"));
    }

    @Test
    void deleteRemovesData() {
        storage.save("players", "abc", new byte[]{1});
        storage.delete("players", "abc");
        assertNull(storage.load("players", "abc"));
        assertFalse(storage.exists("players", "abc"));
    }

    @Test
    void listIdsReturnsAllSaved() {
        storage.save("players", "a", new byte[]{1});
        storage.save("players", "b", new byte[]{2});
        storage.save("players", "c", new byte[]{3});
        List<String> ids = storage.listIds("players");
        assertEquals(3, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertTrue(ids.contains("c"));
    }

    @Test
    void listIdsEmptyForMissingType() {
        assertTrue(storage.listIds("unknown").isEmpty());
    }

    @Test
    void differentTypesAreIsolated() {
        storage.save("players", "x", new byte[]{1});
        storage.save("guilds", "x", new byte[]{2});
        assertArrayEquals(new byte[]{1}, storage.load("players", "x"));
        assertArrayEquals(new byte[]{2}, storage.load("guilds", "x"));
    }

    @Test
    void saveOverwrites() {
        storage.save("players", "a", new byte[]{1});
        storage.save("players", "a", new byte[]{2});
        assertArrayEquals(new byte[]{2}, storage.load("players", "a"));
    }

    @Test
    void supportsListenersReturnsTrue() {
        assertTrue(storage.supportsListeners());
    }
}
