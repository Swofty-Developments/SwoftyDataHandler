package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;
import net.swofty.validation.ValidationException;
import net.swofty.validation.Validators;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final PlayerField<Integer> COINS = PlayerField.create("test", "coins", Codecs.INT, 0);
    private static final PlayerField<String> RANK = PlayerField.create("test", "rank", Codecs.STRING, "default");
    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Long> ISLAND_BANK = LinkedField.create("test", "bank", Codecs.LONG, 0L, ISLAND);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void transactionCommitsOnSuccess() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 1000);

        api.transaction(player, tx -> {
            tx.set(COINS, 500);
            tx.set(RANK, "vip");
        });

        assertEquals(500, api.get(player, COINS));
        assertEquals("vip", api.get(player, RANK));
    }

    @Test
    void transactionReturnsValue() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 1000);

        boolean result = api.transaction(player, tx -> {
            int coins = tx.get(COINS);
            return coins >= 500;
        });

        assertTrue(result);
    }

    @Test
    void transactionAbortRollsBack() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.set(player, RANK, "default");

        api.transaction(player, tx -> {
            tx.set(COINS, 999);
            tx.set(RANK, "hacker");
            tx.abort();
        });

        assertEquals(100, api.get(player, COINS));
        assertEquals("default", api.get(player, RANK));
    }

    @Test
    void transactionAbortReturnsNull() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);

        String result = api.transaction(player, tx -> {
            tx.abort();
            return "should not return";
        });

        assertNull(result);
    }

    @Test
    void transactionGetReadsCurrentState() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 500);

        api.transaction(player, tx -> {
            assertEquals(500, (int) tx.get(COINS));
        });
    }

    @Test
    void transactionGetReadsSetValues() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 500);

        api.transaction(player, tx -> {
            tx.set(COINS, 300);
            assertEquals(300, (int) tx.get(COINS));
        });
    }

    @Test
    void transactionUpdateChains() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);

        api.transaction(player, tx -> {
            tx.update(COINS, c -> c + 50);
            tx.update(COINS, c -> c * 2);
        });

        assertEquals(300, api.get(player, COINS));
    }

    @Test
    void transactionValidationFailureRollsBack() {
        PlayerField<Integer> validated = PlayerField.<Integer>builder("test", "tx_val")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, validated, 100);
        api.set(player, COINS, 500);

        assertThrows(ValidationException.class, () ->
                api.transaction(player, tx -> {
                    tx.set(COINS, 999);
                    tx.set(validated, -1); // Fails validation
                }));

        // Both should be unchanged
        assertEquals(100, api.get(player, validated));
        assertEquals(500, api.get(player, COINS));
    }

    @Test
    void transactionExceptionRollsBack() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);

        assertThrows(RuntimeException.class, () ->
                api.transaction(player, (net.swofty.transaction.TransactionConsumer) tx -> {
                    tx.set(COINS, 999);
                    throw new RuntimeException("something broke");
                }));

        assertEquals(100, api.get(player, COINS));
    }

    @Test
    void transactionWithLinkedData() {
        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();

        api.link(player, ISLAND, islandId);
        api.set(player, COINS, 1000);
        api.setDirect(islandId, ISLAND_BANK, 5000L);

        api.transaction(player, tx -> {
            long bank = tx.get(ISLAND_BANK);
            int coins = tx.get(COINS);

            tx.set(ISLAND_BANK, bank - 500);
            tx.set(COINS, coins + 500);
        });

        assertEquals(1500, api.get(player, COINS));
        assertEquals(4500L, api.getDirect(islandId, ISLAND_BANK));
    }

    @Test
    void purchasePattern() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 1000);
        api.set(player, RANK, "default");

        boolean purchased = api.transaction(player, tx -> {
            int coins = tx.get(COINS);
            if (coins < 500) {
                tx.abort();
                return false;
            }
            tx.set(COINS, coins - 500);
            tx.set(RANK, "vip");
            return true;
        });

        assertTrue(purchased);
        assertEquals(500, api.get(player, COINS));
        assertEquals("vip", api.get(player, RANK));
    }

    @Test
    void purchasePatternInsufficientFunds() {
        UUID player = UUID.randomUUID();
        api.set(player, COINS, 100);
        api.set(player, RANK, "default");

        Boolean purchased = api.transaction(player, tx -> {
            int coins = tx.get(COINS);
            if (coins < 500) {
                tx.abort();
                return false;
            }
            tx.set(COINS, coins - 500);
            tx.set(RANK, "vip");
            return true;
        });

        assertNull(purchased); // abort returns null
        assertEquals(100, api.get(player, COINS));
        assertEquals("default", api.get(player, RANK));
    }
}
