package net.swofty;

import net.swofty.data.format.JsonFormat;
import net.swofty.data.format.BinaryFormat;
import net.swofty.storage.DataStorage;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    @TempDir
    Path tempDir;

    // ==================== FileDataStorage ====================

    @Test
    void saveAndLoad() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        byte[] data = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);

        storage.save("players", "test-id", data);
        byte[] loaded = storage.load("players", "test-id");

        assertNotNull(loaded);
        assertEquals("{\"key\":\"value\"}", new String(loaded, StandardCharsets.UTF_8));
    }

    @Test
    void loadNonExistentReturnsNull() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        assertNull(storage.load("players", "nonexistent"));
    }

    @Test
    void existsReturnsTrueAfterSave() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "test-id", "data".getBytes());
        assertTrue(storage.exists("players", "test-id"));
    }

    @Test
    void existsReturnsFalseWhenNotSaved() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        assertFalse(storage.exists("players", "nonexistent"));
    }

    @Test
    void deleteRemovesData() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "test-id", "data".getBytes());
        assertTrue(storage.exists("players", "test-id"));

        storage.delete("players", "test-id");
        assertFalse(storage.exists("players", "test-id"));
        assertNull(storage.load("players", "test-id"));
    }

    @Test
    void deleteNonExistentDoesNotThrow() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        assertDoesNotThrow(() -> storage.delete("players", "nonexistent"));
    }

    @Test
    void listIdsReturnsAllSavedIds() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "id1", "data1".getBytes());
        storage.save("players", "id2", "data2".getBytes());
        storage.save("players", "id3", "data3".getBytes());

        List<String> ids = storage.listIds("players");
        assertEquals(3, ids.size());
        assertTrue(ids.contains("id1"));
        assertTrue(ids.contains("id2"));
        assertTrue(ids.contains("id3"));
    }

    @Test
    void listIdsEmptyDirectory() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        List<String> ids = storage.listIds("players");
        assertTrue(ids.isEmpty());
    }

    @Test
    void listIdsNonExistentType() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        List<String> ids = storage.listIds("nonexistent_type");
        assertTrue(ids.isEmpty());
    }

    @Test
    void differentTypesAreIsolated() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "id1", "player_data".getBytes());
        storage.save("linked/island", "id1", "island_data".getBytes());

        assertEquals("player_data", new String(storage.load("players", "id1")));
        assertEquals("island_data", new String(storage.load("linked/island", "id1")));
    }

    @Test
    void saveOverwrites() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "id1", "old".getBytes());
        storage.save("players", "id1", "new".getBytes());

        assertEquals("new", new String(storage.load("players", "id1")));
    }

    @Test
    void listIdsAfterDelete() {
        DataStorage storage = new FileDataStorage(tempDir, new JsonFormat(), ".json");
        storage.save("players", "id1", "data".getBytes());
        storage.save("players", "id2", "data".getBytes());

        storage.delete("players", "id1");

        List<String> ids = storage.listIds("players");
        assertEquals(1, ids.size());
        assertTrue(ids.contains("id2"));
    }

    @Test
    void customExtension() {
        DataStorage storage = new FileDataStorage(tempDir, new BinaryFormat(), ".bin");
        storage.save("data", "test", "binary".getBytes());
        assertTrue(storage.exists("data", "test"));
        assertEquals("binary", new String(storage.load("data", "test")));
    }

    // ==================== JsonFormat Round-Trip ====================

    @Test
    void jsonFormatRoundTrip() {
        JsonFormat format = new JsonFormat();
        var writer = format.createWriter();
        writer.writeSection("test:field").writeInt(42);

        byte[] bytes = format.toBytes(writer);
        var reader = format.createReader(bytes);

        assertTrue(reader.hasKey("test:field"));
        assertEquals(42, reader.readSection("test:field").readInt());
    }

    @Test
    void jsonFormatEmptyData() {
        JsonFormat format = new JsonFormat();
        var writer = format.createWriter();
        byte[] bytes = format.toBytes(writer);

        var reader = format.createReader(bytes);
        assertFalse(reader.hasKey("nonexistent"));
    }

    // ==================== BinaryFormat Round-Trip ====================

    @Test
    void binaryFormatPrimitives() {
        BinaryFormat format = new BinaryFormat();
        var writer = format.createWriter();
        writer.writeInt(42);
        writer.writeString("hello");
        writer.writeBoolean(true);
        writer.writeLong(123456789L);

        byte[] bytes = format.toBytes(writer);
        var reader = format.createReader(bytes);

        assertEquals(42, reader.readInt());
        assertEquals("hello", reader.readString());
        assertTrue(reader.readBoolean());
        assertEquals(123456789L, reader.readLong());
    }
}
