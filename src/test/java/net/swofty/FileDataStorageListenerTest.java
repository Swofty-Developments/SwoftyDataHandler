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
    void subscribePlayerFieldThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                api.subscribe(COINS, (p, old, nw) -> {}));
    }

    @Test
    void subscribeLinkedFieldThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> {}));
    }

    @Test
    void subscribeLinkTypeThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                api.subscribe(ISLAND, new LinkChangeListener<>() {
                    @Override
                    public void onLinked(UUID p, LinkType<UUID> type, UUID key) {}
                    @Override
                    public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {}
                }));
    }

    @Test
    void subscribeExpirationThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                api.subscribeExpiration(BOOST, (playerId, field, expiredValue) -> {}));
    }

    @Test
    void subscribeLinkedExpirationThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                api.subscribeExpiration(ISLAND_BOOST, (linkKey, field, expiredValue, memberIds) -> {}));
    }

    @Test
    void dataOperationsStillWork() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        assertEquals(100, api.get(player, COINS));
    }
}
