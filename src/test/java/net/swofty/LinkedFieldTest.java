package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LinkedFieldTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final PlayerField<UUID> GUILD_ID = PlayerField.create("test", "guild_id", Codecs.nullable(Codecs.UUID), null);

    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkType<UUID> GUILD = LinkType.create("guild", Codecs.UUID, GUILD_ID);

    private static final LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create("test", "level", Codecs.INT, 1, ISLAND);
    private static final LinkedField<UUID, Long> ISLAND_BANK = LinkedField.create("test", "bank", Codecs.LONG, 0L, ISLAND);
    private static final LinkedField<UUID, String> GUILD_NAME = LinkedField.create("test", "name", Codecs.STRING, "", GUILD);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    // ==================== Link Management ====================

    @Test
    void linkCreatesAssociation() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        Optional<UUID> key = api.getLinkKey(player, ISLAND);

        assertTrue(key.isPresent());
        assertEquals(islandId, key.get());
    }

    @Test
    void unlinkRemovesAssociation() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.unlink(player, ISLAND);

        assertFalse(api.getLinkKey(player, ISLAND).isPresent());
    }

    @Test
    void getLinkKeyReturnsEmptyWhenNotLinked() {
        UUID player = UUID.randomUUID();
        assertFalse(api.getLinkKey(player, ISLAND).isPresent());
    }

    @Test
    void multipleLinkTypesAreIndependent() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        UUID guildId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.link(player, GUILD, guildId);

        assertEquals(islandId, api.getLinkKey(player, ISLAND).orElse(null));
        assertEquals(guildId, api.getLinkKey(player, GUILD).orElse(null));

        api.unlink(player, ISLAND);
        assertFalse(api.getLinkKey(player, ISLAND).isPresent());
        assertTrue(api.getLinkKey(player, GUILD).isPresent());
    }

    @Test
    void relinkUpdatesAssociation() {
        UUID player = UUID.randomUUID();
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();

        api.link(player, ISLAND, island1);
        assertEquals(island1, api.getLinkKey(player, ISLAND).orElse(null));

        api.unlink(player, ISLAND);
        api.link(player, ISLAND, island2);
        assertEquals(island2, api.getLinkKey(player, ISLAND).orElse(null));
    }

    // ==================== Linked Data Access ====================

    @Test
    void getReturnsDefaultWhenNotLinked() {
        UUID player = UUID.randomUUID();
        assertEquals(1, api.get(player, ISLAND_LEVEL));
    }

    @Test
    void setAndGetViaPlayer() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.set(player, ISLAND_LEVEL, 5);

        assertEquals(5, api.get(player, ISLAND_LEVEL));
    }

    @Test
    void setAndGetDirect() {
        UUID islandId = UUID.randomUUID();

        api.setDirect(islandId, ISLAND_LEVEL, 10);
        assertEquals(10, api.getDirect(islandId, ISLAND_LEVEL));
    }

    @Test
    void updateViaPlayer() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 1);
        api.update(player, ISLAND_LEVEL, level -> level + 1);

        assertEquals(2, api.get(player, ISLAND_LEVEL));
    }

    @Test
    void updateDirect() {
        UUID islandId = UUID.randomUUID();

        api.setDirect(islandId, ISLAND_LEVEL, 5);
        api.updateDirect(islandId, ISLAND_LEVEL, level -> level * 2);

        assertEquals(10, api.getDirect(islandId, ISLAND_LEVEL));
    }

    // ==================== Shared Data ====================

    @Test
    void linkedPlayersShareData() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(p1, ISLAND, islandId);
        api.link(p2, ISLAND, islandId);
        api.link(p3, ISLAND, islandId);

        api.setDirect(islandId, ISLAND_LEVEL, 10);

        assertEquals(10, api.get(p1, ISLAND_LEVEL));
        assertEquals(10, api.get(p2, ISLAND_LEVEL));
        assertEquals(10, api.get(p3, ISLAND_LEVEL));
    }

    @Test
    void updateByOnePlayerAffectsAll() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(p1, ISLAND, islandId);
        api.link(p2, ISLAND, islandId);

        api.setDirect(islandId, ISLAND_LEVEL, 5);
        api.update(p1, ISLAND_LEVEL, level -> level + 1);

        assertEquals(6, api.get(p1, ISLAND_LEVEL));
        assertEquals(6, api.get(p2, ISLAND_LEVEL));
    }

    @Test
    void differentLinkedGroupsAreIndependent() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();

        api.link(p1, ISLAND, island1);
        api.link(p2, ISLAND, island2);

        api.setDirect(island1, ISLAND_LEVEL, 5);
        api.setDirect(island2, ISLAND_LEVEL, 10);

        assertEquals(5, api.get(p1, ISLAND_LEVEL));
        assertEquals(10, api.get(p2, ISLAND_LEVEL));
    }

    @Test
    void unlinkDoesNotAffectOtherMembers() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(p1, ISLAND, islandId);
        api.link(p2, ISLAND, islandId);

        api.setDirect(islandId, ISLAND_LEVEL, 5);
        api.unlink(p1, ISLAND);

        assertEquals(1, api.get(p1, ISLAND_LEVEL)); // default since unlinked
        assertEquals(5, api.get(p2, ISLAND_LEVEL)); // still linked
    }

    @Test
    void setOnUnlinkedPlayerThrows() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () ->
                api.set(player, ISLAND_LEVEL, 5));
    }

    @Test
    void updateOnUnlinkedPlayerThrows() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () ->
                api.update(player, ISLAND_LEVEL, l -> l + 1));
    }

    // ==================== Multiple Fields Per Link ====================

    @Test
    void multipleFieldsPerLink() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 5);
        api.setDirect(islandId, ISLAND_BANK, 1000L);

        assertEquals(5, api.get(player, ISLAND_LEVEL));
        assertEquals(1000L, api.get(player, ISLAND_BANK));
    }

    @Test
    void differentLinkTypesHaveIndependentData() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        UUID guildId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.link(player, GUILD, guildId);

        api.setDirect(islandId, ISLAND_LEVEL, 5);
        api.setDirect(guildId, GUILD_NAME, "Warriors");

        assertEquals(5, api.get(player, ISLAND_LEVEL));
        assertEquals("Warriors", api.get(player, GUILD_NAME));
    }
}
