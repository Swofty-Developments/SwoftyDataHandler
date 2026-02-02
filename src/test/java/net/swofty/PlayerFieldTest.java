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

class PlayerFieldTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("game", "name", Codecs.STRING, "");
    private static final PlayerField<Long> XP = PlayerField.create("game", "xp", Codecs.LONG, 0L);
    private static final PlayerField<Double> MULTIPLIER = PlayerField.create("game", "multiplier", Codecs.DOUBLE, 1.0);
    private static final PlayerField<Boolean> VIP = PlayerField.create("game", "vip", Codecs.BOOL, false);
    private static final PlayerField<Float> SPEED = PlayerField.create("game", "speed", Codecs.FLOAT, 1.0f);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void getReturnsDefaultForNewPlayer() {
        UUID player = UUID.randomUUID();
        assertEquals(0, api.get(player, COINS));
        assertEquals("", api.get(player, NAME));
        assertEquals(0L, api.get(player, XP));
        assertEquals(1.0, api.get(player, MULTIPLIER));
        assertFalse(api.get(player, VIP));
    }

    @Test
    void setAndGetInteger() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 500);
        assertEquals(500, api.get(player, COINS));
    }

    @Test
    void setAndGetString() {
        UUID player = UUID.randomUUID();
        api.set(player, NAME, "Steve");
        assertEquals("Steve", api.get(player, NAME));
    }

    @Test
    void setAndGetLong() {
        UUID player = UUID.randomUUID();
        api.set(player, XP, 999999999L);
        assertEquals(999999999L, api.get(player, XP));
    }

    @Test
    void setAndGetDouble() {
        UUID player = UUID.randomUUID();
        api.set(player, MULTIPLIER, 2.5);
        assertEquals(2.5, api.get(player, MULTIPLIER));
    }

    @Test
    void setAndGetBoolean() {
        UUID player = UUID.randomUUID();
        api.set(player, VIP, true);
        assertTrue(api.get(player, VIP));
    }

    @Test
    void setAndGetFloat() {
        UUID player = UUID.randomUUID();
        api.set(player, SPEED, 1.5f);
        assertEquals(1.5f, api.get(player, SPEED), 0.001f);
    }

    @Test
    void updateAppliesOperator() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.update(player, COINS, c -> c + 50);
        assertEquals(150, api.get(player, COINS));
    }

    @Test
    void updateFromDefault() {
        UUID player = UUID.randomUUID();
        api.update(player, COINS, c -> c + 10);
        assertEquals(10, api.get(player, COINS));
    }

    @Test
    void multipleUpdatesAccumulate() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 0);
        for (int i = 0; i < 10; i++) {
            api.update(player, COINS, c -> c + 1);
        }
        assertEquals(10, api.get(player, COINS));
    }

    @Test
    void setOverwritesPreviousValue() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.set(player, COINS, 200);
        assertEquals(200, api.get(player, COINS));
    }

    @Test
    void multipleFieldsAreIndependent() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.set(player, NAME, "Steve");
        api.set(player, XP, 500L);

        assertEquals(100, api.get(player, COINS));
        assertEquals("Steve", api.get(player, NAME));
        assertEquals(500L, api.get(player, XP));

        api.set(player, COINS, 200);
        assertEquals(200, api.get(player, COINS));
        assertEquals("Steve", api.get(player, NAME));
    }

    @Test
    void playersAreIsolated() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 200);

        assertEquals(100, api.get(p1, COINS));
        assertEquals(200, api.get(p2, COINS));

        api.update(p1, COINS, c -> c + 50);
        assertEquals(150, api.get(p1, COINS));
        assertEquals(200, api.get(p2, COINS));
    }

    @Test
    void emptyStringFieldWorks() {
        UUID player = UUID.randomUUID();
        api.set(player, NAME, "");
        assertEquals("", api.get(player, NAME));
    }

    @Test
    void largeIntegerValue() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, api.get(player, COINS));
    }

    @Test
    void negativeIntegerValue() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, -1000);
        assertEquals(-1000, api.get(player, COINS));
    }

    @Test
    void specialCharactersInString() {
        UUID player = UUID.randomUUID();
        api.set(player, NAME, "Test \"quotes\" and \\backslash");
        assertEquals("Test \"quotes\" and \\backslash", api.get(player, NAME));
    }

    @Test
    void unicodeString() {
        UUID player = UUID.randomUUID();
        api.set(player, NAME, "\u00e9\u00e8\u00ea\u00eb");
        assertEquals("\u00e9\u00e8\u00ea\u00eb", api.get(player, NAME));
    }
}
