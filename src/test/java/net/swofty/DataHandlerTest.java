package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.InMemoryDataStorage;
import net.swofty.validation.ValidationException;
import net.swofty.validation.Validators;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DataHandlerTest {

    private DataAPIImpl api;

    // Test fields
    private static final PlayerField<Integer> COINS = PlayerField.create("test", "coins", Codecs.INT, 0);
    private static final PlayerField<String> NAME = PlayerField.create("test", "name", Codecs.STRING, "");
    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);

    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create("test", "level", Codecs.INT, 1, ISLAND);
    private static final LinkedField<UUID, Long> ISLAND_BANK = LinkedField.create("test", "bank", Codecs.LONG, 0L, ISLAND);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new InMemoryDataStorage());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    // ==================== Player Field Tests ====================

    @Test
    void testGetDefaultValue() {
        UUID player = UUID.randomUUID();
        assertEquals(0, api.get(player, COINS));
        assertEquals("", api.get(player, NAME));
    }

    @Test
    void testSetAndGet() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 500);
        assertEquals(500, api.get(player, COINS));
    }

    @Test
    void testUpdate() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.update(player, COINS, c -> c + 50);
        assertEquals(150, api.get(player, COINS));
    }

    @Test
    void testMultipleFields() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.set(player, NAME, "Steve");
        assertEquals(100, api.get(player, COINS));
        assertEquals("Steve", api.get(player, NAME));
    }

    @Test
    void testMultiplePlayers() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        api.set(p1, COINS, 100);
        api.set(p2, COINS, 200);
        assertEquals(100, api.get(p1, COINS));
        assertEquals(200, api.get(p2, COINS));
    }

    // ==================== Namespace Tests ====================

    @Test
    void testNamespacing() {
        PlayerField<Integer> coins1 = PlayerField.create("plugin1", "coins", Codecs.INT, 0);
        PlayerField<Integer> coins2 = PlayerField.create("plugin2", "coins", Codecs.INT, 0);

        assertEquals("plugin1:coins", coins1.fullKey());
        assertEquals("plugin2:coins", coins2.fullKey());

        UUID player = UUID.randomUUID();
        api.set(player, coins1, 100);
        api.set(player, coins2, 200);
        assertEquals(100, api.get(player, coins1));
        assertEquals(200, api.get(player, coins2));
    }

    // ==================== Validation Tests ====================

    @Test
    void testValidation() {
        PlayerField<Integer> validated = PlayerField.<Integer>builder("test", "validated")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, validated, 100);
        assertEquals(100, api.get(player, validated));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                api.set(player, validated, -1));
        assertEquals("Value cannot be negative", ex.getMessage());
        assertEquals(validated, ex.getField());
        assertEquals(-1, ex.getValue());
    }

    @Test
    void testRangeValidator() {
        PlayerField<Integer> level = PlayerField.<Integer>builder("test", "level")
                .codec(Codecs.INT)
                .defaultValue(1)
                .validator(Validators.range(1, 100))
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, level, 50);
        assertEquals(50, api.get(player, level));

        assertThrows(ValidationException.class, () -> api.set(player, level, 0));
        assertThrows(ValidationException.class, () -> api.set(player, level, 101));
    }

    @Test
    void testComposedValidators() {
        PlayerField<String> nickname = PlayerField.<String>builder("test", "nickname")
                .codec(Codecs.STRING)
                .defaultValue("")
                .validator(Validators.maxLength(16).and(value ->
                        value.matches("^[a-zA-Z0-9_]*$")
                                ? net.swofty.validation.ValidationResult.valid()
                                : net.swofty.validation.ValidationResult.invalid("Invalid characters")))
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, nickname, "ValidName");
        assertEquals("ValidName", api.get(player, nickname));

        assertThrows(ValidationException.class, () ->
                api.set(player, nickname, "way_too_long_nickname_here"));
        assertThrows(ValidationException.class, () ->
                api.set(player, nickname, "bad name!"));
    }

    // ==================== Linked Data Tests ====================

    @Test
    void testLinkedData() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 5);

        assertEquals(5, api.get(player, ISLAND_LEVEL));
    }

    @Test
    void testLinkedDataSharedBetweenPlayers() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(p1, ISLAND, islandId);
        api.link(p2, ISLAND, islandId);

        api.setDirect(islandId, ISLAND_LEVEL, 10);

        assertEquals(10, api.get(p1, ISLAND_LEVEL));
        assertEquals(10, api.get(p2, ISLAND_LEVEL));
    }

    @Test
    void testLinkedDataUpdate() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 1);
        api.update(player, ISLAND_LEVEL, level -> level + 1);

        assertEquals(2, api.get(player, ISLAND_LEVEL));
    }

    @Test
    void testUnlink() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        assertTrue(api.getLinkKey(player, ISLAND).isPresent());

        api.unlink(player, ISLAND);
        assertFalse(api.getLinkKey(player, ISLAND).isPresent());
    }

    // ==================== Event Tests ====================

    @Test
    void testPlayerDataListener() {
        UUID player = UUID.randomUUID();
        AtomicReference<Integer> oldVal = new AtomicReference<>();
        AtomicReference<Integer> newVal = new AtomicReference<>();

        api.subscribe(COINS, (p, old, nw) -> {
            oldVal.set(old);
            newVal.set(nw);
        });

        api.set(player, COINS, 100);
        assertEquals(0, oldVal.get());
        assertEquals(100, newVal.get());
    }

    @Test
    void testLinkedDataListener() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicReference<Integer> oldVal = new AtomicReference<>();
        AtomicReference<Integer> newVal = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean(false);

        api.subscribe(ISLAND_LEVEL, (key, old, nw, affected) -> {
            oldVal.set(old);
            newVal.set(nw);
            fired.set(true);
        });

        api.link(player, ISLAND, islandId);
        api.setDirect(islandId, ISLAND_LEVEL, 5);

        assertTrue(fired.get());
        assertEquals(1, oldVal.get()); // default value
        assertEquals(5, newVal.get());
    }

    @Test
    void testLinkChangeListener() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        AtomicBoolean linked = new AtomicBoolean(false);
        AtomicBoolean unlinked = new AtomicBoolean(false);

        api.subscribe(ISLAND, new net.swofty.event.LinkChangeListener<>() {
            @Override
            public void onLinked(UUID p, LinkType<UUID> type, UUID key) {
                linked.set(true);
            }

            @Override
            public void onUnlinked(UUID p, LinkType<UUID> type, UUID previousKey) {
                unlinked.set(true);
            }
        });

        api.link(player, ISLAND, islandId);
        assertTrue(linked.get());

        api.unlink(player, ISLAND);
        assertTrue(unlinked.get());
    }

    // ==================== Transaction Tests ====================

    @Test
    void testTransactionSuccess() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 1000);

        boolean result = api.transaction(player, tx -> {
            int coins = tx.get(COINS);
            if (coins >= 500) {
                tx.set(COINS, coins - 500);
                return true;
            }
            return false;
        });

        assertTrue(result);
        assertEquals(500, api.get(player, COINS));
    }

    @Test
    void testTransactionAbort() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);

        api.transaction(player, tx -> {
            int coins = tx.get(COINS);
            if (coins < 500) {
                tx.abort();
            }
            tx.set(COINS, coins - 500);
        });

        // Coins should remain unchanged after abort
        assertEquals(100, api.get(player, COINS));
    }

    @Test
    void testTransactionRollbackOnValidationFailure() {
        PlayerField<Integer> validated = PlayerField.<Integer>builder("test", "tx_validated")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, validated, 100);

        assertThrows(ValidationException.class, () ->
                api.transaction(player, tx -> {
                    tx.set(validated, -1); // Should fail validation
                }));

        assertEquals(100, api.get(player, validated));
    }

    // ==================== Expiring Data Tests ====================

    @Test
    void testExpiringField() {
        ExpiringField<Boolean> boost = ExpiringField.<Boolean>expiringBuilder("test", "boost")
                .codec(Codecs.BOOL)
                .defaultValue(false)
                .defaultTtl(Duration.ofHours(1))
                .build();

        UUID player = UUID.randomUUID();

        assertTrue(api.isExpired(player, boost));

        api.set(player, boost, true);
        assertFalse(api.isExpired(player, boost));
        assertTrue(api.getTimeRemaining(player, boost).isPresent());
    }

    @Test
    void testExpiringFieldCustomTtl() {
        ExpiringField<Boolean> boost = ExpiringField.<Boolean>expiringBuilder("test", "boost2")
                .codec(Codecs.BOOL)
                .defaultValue(false)
                .defaultTtl(Duration.ofHours(1))
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, boost, true, Duration.ofMinutes(30));

        assertFalse(api.isExpired(player, boost));
        Duration remaining = api.getTimeRemaining(player, boost).orElseThrow();
        assertTrue(remaining.toMinutes() <= 30);
        assertTrue(remaining.toMinutes() >= 29);
    }

    @Test
    void testExtendExpiration() {
        ExpiringField<Boolean> boost = ExpiringField.<Boolean>expiringBuilder("test", "boost3")
                .codec(Codecs.BOOL)
                .defaultValue(false)
                .defaultTtl(Duration.ofHours(1))
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, boost, true, Duration.ofMinutes(30));

        api.extend(player, boost, Duration.ofMinutes(30));

        Duration remaining = api.getTimeRemaining(player, boost).orElseThrow();
        assertTrue(remaining.toMinutes() >= 59);
    }

    // ==================== Bulk Operations Tests ====================

    @Test
    void testLeaderboard() {
        for (int i = 0; i < 5; i++) {
            UUID player = UUID.randomUUID();
            api.set(player, COINS, (i + 1) * 100);
        }

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 3);
        assertEquals(3, top.size());
        assertEquals(500, top.get(0).value());
        assertEquals(400, top.get(1).value());
        assertEquals(300, top.get(2).value());
        assertEquals(1, top.get(0).rank());
        assertEquals(2, top.get(1).rank());
        assertEquals(3, top.get(2).rank());
    }

    @Test
    void testQuery() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 500);
        api.set(p3, COINS, 1500);

        List<UUID> rich = api.query(COINS, c -> c > 200);
        assertEquals(2, rich.size());
        assertTrue(rich.contains(p2));
        assertTrue(rich.contains(p3));
    }

    @Test
    void testCount() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 500);
        api.set(p3, COINS, 1500);

        assertEquals(2, api.count(COINS, c -> c > 200));
    }

    @Test
    void testUpdateAll() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 200);

        int affected = api.updateAll(COINS, c -> c * 2);
        assertEquals(2, affected);
        assertEquals(200, api.get(p1, COINS));
        assertEquals(400, api.get(p2, COINS));
    }

    @Test
    void testUpdateWhere() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 500);
        api.set(p3, COINS, 1500);

        int affected = api.updateWhere(COINS, c -> c > 200, c -> c + 100);
        assertEquals(2, affected);
        assertEquals(100, api.get(p1, COINS));
        assertEquals(600, api.get(p2, COINS));
        assertEquals(1600, api.get(p3, COINS));
    }

    @Test
    void testPagination() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            api.set(player, COINS, (i + 1) * 10);
        }

        Page<LeaderboardEntry<Integer>> page1 = api.getTopPaged(COINS, 1, 10);
        assertEquals(10, page1.content().size());
        assertEquals(1, page1.page());
        assertEquals(3, page1.totalPages());
        assertEquals(25, page1.totalElements());
        assertEquals(250, page1.content().get(0).value());

        Page<LeaderboardEntry<Integer>> page3 = api.getTopPaged(COINS, 3, 10);
        assertEquals(5, page3.content().size());
    }

    // ==================== Codec Tests ====================

    @Test
    void testNullableCodec() {
        PlayerField<UUID> nullable = PlayerField.create("test", "nullable_uuid", Codecs.nullable(Codecs.UUID), null);
        UUID player = UUID.randomUUID();

        assertNull(api.get(player, nullable));

        UUID value = UUID.randomUUID();
        api.set(player, nullable, value);
        assertEquals(value, api.get(player, nullable));
    }

    // ==================== Builder Tests ====================

    @Test
    void testPlayerFieldBuilder() {
        PlayerField<Integer> field = PlayerField.<Integer>builder("ns", "key")
                .codec(Codecs.INT)
                .defaultValue(42)
                .build();

        assertEquals("ns", field.namespace());
        assertEquals("key", field.key());
        assertEquals("ns:key", field.fullKey());
        assertEquals(42, field.defaultValue());
    }

    @Test
    void testLinkedFieldBuilder() {
        LinkedField<UUID, Integer> field = LinkedField.<UUID, Integer>builder("ns", "key", ISLAND)
                .codec(Codecs.INT)
                .defaultValue(0)
                .build();

        assertEquals("ns", field.namespace());
        assertEquals("key", field.key());
        assertEquals(ISLAND, field.linkType());
    }
}
