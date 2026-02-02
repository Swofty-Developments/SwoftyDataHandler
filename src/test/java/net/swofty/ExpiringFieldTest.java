package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExpiringFieldTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final ExpiringField<Boolean> DOUBLE_XP = ExpiringField.<Boolean>expiringBuilder("test", "double_xp")
            .codec(Codecs.BOOL)
            .defaultValue(false)
            .defaultTtl(Duration.ofHours(1))
            .build();

    private static final ExpiringField<String> TEMP_TAG = ExpiringField.<String>expiringBuilder("test", "temp_tag")
            .codec(Codecs.STRING)
            .defaultValue("")
            .defaultTtl(Duration.ofMinutes(30))
            .build();

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void neverSetFieldIsExpired() {
        UUID player = UUID.randomUUID();
        assertTrue(api.isExpired(player, DOUBLE_XP));
    }

    @Test
    void neverSetFieldReturnsEmptyTimeRemaining() {
        UUID player = UUID.randomUUID();
        assertFalse(api.getTimeRemaining(player, DOUBLE_XP).isPresent());
    }

    @Test
    void setWithDefaultTtl() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true);

        assertFalse(api.isExpired(player, DOUBLE_XP));
        assertTrue(api.getTimeRemaining(player, DOUBLE_XP).isPresent());
    }

    @Test
    void timeRemainingIsWithinDefaultTtl() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true);

        Duration remaining = api.getTimeRemaining(player, DOUBLE_XP).orElseThrow();
        assertTrue(remaining.toMinutes() <= 60);
        assertTrue(remaining.toMinutes() >= 59);
    }

    @Test
    void setWithCustomTtl() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true, Duration.ofMinutes(15));

        assertFalse(api.isExpired(player, DOUBLE_XP));
        Duration remaining = api.getTimeRemaining(player, DOUBLE_XP).orElseThrow();
        assertTrue(remaining.toMinutes() <= 15);
        assertTrue(remaining.toMinutes() >= 14);
    }

    @Test
    void extendIncreasesRemaining() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true, Duration.ofMinutes(30));

        api.extend(player, DOUBLE_XP, Duration.ofMinutes(30));

        Duration remaining = api.getTimeRemaining(player, DOUBLE_XP).orElseThrow();
        assertTrue(remaining.toMinutes() >= 59);
    }

    @Test
    void extendWhenExpiredThrows() {
        UUID player = UUID.randomUUID();
        // Never set, so expired
        assertThrows(IllegalStateException.class, () ->
                api.extend(player, DOUBLE_XP, Duration.ofMinutes(30)));
    }

    @Test
    void getExpiredFieldReturnsDefault() {
        UUID player = UUID.randomUUID();
        // The default is false for DOUBLE_XP
        assertTrue(api.isExpired(player, DOUBLE_XP));
        assertEquals(false, api.get(player, DOUBLE_XP));
    }

    @Test
    void multipleExpiringFieldsAreIndependent() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true, Duration.ofMinutes(60));
        api.set(player, TEMP_TAG, "special", Duration.ofMinutes(5));

        assertFalse(api.isExpired(player, DOUBLE_XP));
        assertFalse(api.isExpired(player, TEMP_TAG));

        Duration xpRemaining = api.getTimeRemaining(player, DOUBLE_XP).orElseThrow();
        Duration tagRemaining = api.getTimeRemaining(player, TEMP_TAG).orElseThrow();

        assertTrue(xpRemaining.toMinutes() > tagRemaining.toMinutes());
    }

    @Test
    void expiringFieldPerPlayer() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        api.set(p1, DOUBLE_XP, true, Duration.ofMinutes(60));

        assertFalse(api.isExpired(p1, DOUBLE_XP));
        assertTrue(api.isExpired(p2, DOUBLE_XP));
    }

    @Test
    void resettingExpiringFieldResetsTimer() {
        UUID player = UUID.randomUUID();
        api.set(player, DOUBLE_XP, true, Duration.ofMinutes(10));
        api.set(player, DOUBLE_XP, true, Duration.ofMinutes(60));

        Duration remaining = api.getTimeRemaining(player, DOUBLE_XP).orElseThrow();
        assertTrue(remaining.toMinutes() >= 59);
    }

    @Test
    void builderCreatesCorrectField() {
        assertEquals("test", DOUBLE_XP.namespace());
        assertEquals("double_xp", DOUBLE_XP.key());
        assertEquals(false, DOUBLE_XP.defaultValue());
        assertEquals(Duration.ofHours(1), DOUBLE_XP.defaultTtl());
    }

    // ==================== Expiring Linked Fields ====================

    @Test
    void expiringLinkedField() {
        PlayerField<UUID> islandIdField = PlayerField.create("test", "island_id_exp", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> island = LinkType.create("island_exp", Codecs.UUID, islandIdField);

        ExpiringLinkedField<UUID, Boolean> shield = ExpiringLinkedField.<UUID, Boolean>expiringBuilder("test", "shield", island)
                .codec(Codecs.BOOL)
                .defaultValue(false)
                .defaultTtl(Duration.ofHours(12))
                .build();

        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, island, islandId);
        api.set(player, shield, true, Duration.ofHours(6));

        assertEquals(true, api.get(player, shield));
    }

    @Test
    void expiringLinkedFieldBuilder() {
        PlayerField<UUID> islandIdField = PlayerField.create("test", "island_id_exp2", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> island = LinkType.create("island_exp2", Codecs.UUID, islandIdField);

        ExpiringLinkedField<UUID, Boolean> field = ExpiringLinkedField.<UUID, Boolean>expiringBuilder("test", "test_field", island)
                .codec(Codecs.BOOL)
                .defaultValue(false)
                .defaultTtl(Duration.ofHours(6))
                .build();

        assertEquals("test", field.namespace());
        assertEquals("test_field", field.key());
        assertEquals(false, field.defaultValue());
        assertEquals(Duration.ofHours(6), field.defaultTtl());
        assertEquals(island, field.linkType());
    }
}
