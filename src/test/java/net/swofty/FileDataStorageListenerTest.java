package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.event.LinkChangeListener;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FileDataStorageListenerTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final PlayerField<Integer> COINS = PlayerField.create("test", "coins", Codecs.INT, 0);
    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create("test", "level", Codecs.INT, 1, ISLAND);

    private static final ExpiringField<Integer> BOOST = ExpiringField.<Integer>expiringBuilder("test", "boost")
            .codec(Codecs.INT).defaultValue(0).defaultTtl(Duration.ofMinutes(5)).build();

    private static final ExpiringLinkedField<UUID, Integer> ISLAND_BOOST =
            ExpiringLinkedField.<UUID, Integer>expiringBuilder("test", "island_boost", ISLAND)
                    .codec(Codecs.INT).defaultValue(0).defaultTtl(Duration.ofMinutes(5)).build();

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void subscribePlayerFieldWorks() {
        AtomicBoolean fired = new AtomicBoolean(false);
        api.subscribe(COINS, (p, old, nw) -> fired.set(true));
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        assertTrue(fired.get());
    }

    @Test
    void subscribeLinkedFieldWorks() {
        AtomicBoolean fired = new AtomicBoolean(false);
        api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> fired.set(true));
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        api.link(player, ISLAND, islandId);
        api.set(player, ISLAND_LEVEL, 5);
        assertTrue(fired.get());
    }

    @Test
    void subscribeLinkTypeWorks() {
        AtomicBoolean fired = new AtomicBoolean(false);
        api.subscribe(ISLAND, new LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) { fired.set(true); }
            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {}
        });
        UUID player = UUID.randomUUID();
        api.link(player, ISLAND, UUID.randomUUID());
        assertTrue(fired.get());
    }

    @Test
    void subscribeExpirationDoesNotThrow() {
        assertDoesNotThrow(() ->
                api.subscribeExpiration(BOOST, (playerId, field, expiredValue) -> {}));
    }

    @Test
    void subscribeLinkedExpirationDoesNotThrow() {
        assertDoesNotThrow(() ->
                api.subscribeExpiration(ISLAND_BOOST, (linkKey, field, expiredValue, memberIds) -> {}));
    }

    @Test
    void dataOperationsStillWork() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        assertEquals(100, api.get(player, COINS));
    }
}
