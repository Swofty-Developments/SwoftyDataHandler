package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NamespaceTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void fullKeyFormat() {
        PlayerField<Integer> field = PlayerField.create("myplugin", "coins", Codecs.INT, 0);
        assertEquals("myplugin:coins", field.fullKey());
    }

    @Test
    void sameKeyDifferentNamespacesAreIndependent() {
        PlayerField<Integer> coins1 = PlayerField.create("plugin1", "coins", Codecs.INT, 0);
        PlayerField<Integer> coins2 = PlayerField.create("plugin2", "coins", Codecs.INT, 0);

        UUID player = UUID.randomUUID();
        api.set(player, coins1, 100);
        api.set(player, coins2, 200);

        assertEquals(100, api.get(player, coins1));
        assertEquals(200, api.get(player, coins2));
    }

    @Test
    void sameNamespaceDifferentKeysAreIndependent() {
        PlayerField<Integer> coins = PlayerField.create("ns", "coins", Codecs.INT, 0);
        PlayerField<Integer> gems = PlayerField.create("ns", "gems", Codecs.INT, 0);

        UUID player = UUID.randomUUID();
        api.set(player, coins, 100);
        api.set(player, gems, 50);

        assertEquals(100, api.get(player, coins));
        assertEquals(50, api.get(player, gems));
    }

    @Test
    void differentNamespaceDifferentDefaultValues() {
        PlayerField<Integer> a = PlayerField.create("ns1", "val", Codecs.INT, 10);
        PlayerField<Integer> b = PlayerField.create("ns2", "val", Codecs.INT, 20);

        UUID player = UUID.randomUUID();
        assertEquals(10, api.get(player, a));
        assertEquals(20, api.get(player, b));
    }

    @Test
    void modifyingOneNamespaceDoesNotAffectOther() {
        PlayerField<String> name1 = PlayerField.create("plugin1", "name", Codecs.STRING, "default1");
        PlayerField<String> name2 = PlayerField.create("plugin2", "name", Codecs.STRING, "default2");

        UUID player = UUID.randomUUID();
        api.set(player, name1, "changed");

        assertEquals("changed", api.get(player, name1));
        assertEquals("default2", api.get(player, name2));
    }
}
