package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BulkOperationsTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    private static final PlayerField<Integer> COINS = PlayerField.create("test", "coins", Codecs.INT, 0);
    private static final PlayerField<String> RANK = PlayerField.create("test", "rank", Codecs.STRING, "default");
    private static final PlayerField<UUID> ISLAND_ID = PlayerField.create("test", "island_id", Codecs.nullable(Codecs.UUID), null);
    private static final LinkType<UUID> ISLAND = LinkType.create("island", Codecs.UUID, ISLAND_ID);
    private static final LinkedField<UUID, Integer> ISLAND_LEVEL = LinkedField.create("test", "level", Codecs.INT, 1, ISLAND);

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    // ==================== Leaderboard ====================

    @Test
    void getTopReturnsDescendingOrder() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID p = UUID.randomUUID();
            players.add(p);
            api.set(p, COINS, (i + 1) * 100);
        }

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 5);
        assertEquals(5, top.size());
        for (int i = 0; i < top.size() - 1; i++) {
            assertTrue(top.get(i).value() >= top.get(i + 1).value());
        }
    }

    @Test
    void getTopLimitsResults() {
        for (int i = 0; i < 10; i++) {
            api.set(UUID.randomUUID(), COINS, i * 100);
        }

        List<LeaderboardEntry<Integer>> top3 = api.getTop(COINS, 3);
        assertEquals(3, top3.size());
    }

    @Test
    void getTopWithFewerPlayersThanLimit() {
        api.set(UUID.randomUUID(), COINS, 100);
        api.set(UUID.randomUUID(), COINS, 200);

        List<LeaderboardEntry<Integer>> top10 = api.getTop(COINS, 10);
        assertEquals(2, top10.size());
    }

    @Test
    void getTopRanksAreCorrect() {
        for (int i = 0; i < 5; i++) {
            api.set(UUID.randomUUID(), COINS, (i + 1) * 100);
        }

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 5);
        for (int i = 0; i < top.size(); i++) {
            assertEquals(i + 1, top.get(i).rank());
        }
    }

    @Test
    void getTopWithCustomComparator() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 300);
        api.set(p2, COINS, 100);
        api.set(p3, COINS, 200);

        // Ascending order
        List<LeaderboardEntry<Integer>> ascending = api.getTop(COINS, 3, Comparator.naturalOrder());
        assertEquals(100, ascending.get(0).value());
        assertEquals(200, ascending.get(1).value());
        assertEquals(300, ascending.get(2).value());
    }

    @Test
    void getTopPlayerIdsAreCorrect() {
        UUID rich = UUID.randomUUID();
        UUID poor = UUID.randomUUID();

        api.set(rich, COINS, 1000);
        api.set(poor, COINS, 10);

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 2);
        assertEquals(rich, top.get(0).playerId());
        assertEquals(poor, top.get(1).playerId());
    }

    // ==================== Pagination ====================

    @Test
    void getTopPagedFirstPage() {
        for (int i = 0; i < 25; i++) {
            api.set(UUID.randomUUID(), COINS, (i + 1) * 10);
        }

        Page<LeaderboardEntry<Integer>> page1 = api.getTopPaged(COINS, 1, 10);
        assertEquals(10, page1.content().size());
        assertEquals(1, page1.page());
        assertEquals(3, page1.totalPages());
        assertEquals(25, page1.totalElements());
    }

    @Test
    void getTopPagedLastPagePartial() {
        for (int i = 0; i < 25; i++) {
            api.set(UUID.randomUUID(), COINS, (i + 1) * 10);
        }

        Page<LeaderboardEntry<Integer>> page3 = api.getTopPaged(COINS, 3, 10);
        assertEquals(5, page3.content().size());
        assertEquals(3, page3.page());
    }

    @Test
    void getTopPagedMiddlePage() {
        for (int i = 0; i < 30; i++) {
            api.set(UUID.randomUUID(), COINS, (i + 1) * 10);
        }

        Page<LeaderboardEntry<Integer>> page2 = api.getTopPaged(COINS, 2, 10);
        assertEquals(10, page2.content().size());
        assertEquals(2, page2.page());
        assertEquals(3, page2.totalPages());
        assertEquals(30, page2.totalElements());

        // Verify ranks are correct for page 2 (entries 11-20)
        assertEquals(11, page2.content().get(0).rank());
        assertEquals(20, page2.content().get(9).rank());
    }

    @Test
    void getTopPagedSinglePage() {
        for (int i = 0; i < 5; i++) {
            api.set(UUID.randomUUID(), COINS, (i + 1) * 100);
        }

        Page<LeaderboardEntry<Integer>> page = api.getTopPaged(COINS, 1, 10);
        assertEquals(5, page.content().size());
        assertEquals(1, page.totalPages());
    }

    @Test
    void getTopPagedEmptyDataset() {
        Page<LeaderboardEntry<Integer>> page = api.getTopPaged(COINS, 1, 10);
        assertEquals(0, page.content().size());
        assertEquals(0, page.totalPages());
        assertEquals(0, page.totalElements());
    }

    // ==================== Query ====================

    @Test
    void queryWithPredicate() {
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
        assertFalse(rich.contains(p1));
    }

    @Test
    void queryReturnsEmptyWhenNoMatch() {
        api.set(UUID.randomUUID(), COINS, 10);
        api.set(UUID.randomUUID(), COINS, 20);

        List<UUID> result = api.query(COINS, c -> c > 1000);
        assertTrue(result.isEmpty());
    }

    @Test
    void queryMatchesAll() {
        api.set(UUID.randomUUID(), COINS, 10);
        api.set(UUID.randomUUID(), COINS, 20);

        List<UUID> result = api.query(COINS, c -> c > 0);
        assertEquals(2, result.size());
    }

    @Test
    void queryWithStringField() {
        UUID admin1 = UUID.randomUUID();
        UUID admin2 = UUID.randomUUID();
        UUID normal = UUID.randomUUID();

        api.set(admin1, RANK, "admin");
        api.set(admin2, RANK, "admin");
        api.set(normal, RANK, "player");

        List<UUID> admins = api.query(RANK, r -> r.equals("admin"));
        assertEquals(2, admins.size());
        assertTrue(admins.contains(admin1));
        assertTrue(admins.contains(admin2));
    }

    // ==================== Count ====================

    @Test
    void countWithPredicate() {
        api.set(UUID.randomUUID(), COINS, 100);
        api.set(UUID.randomUUID(), COINS, 500);
        api.set(UUID.randomUUID(), COINS, 1500);

        assertEquals(2, api.count(COINS, c -> c > 200));
    }

    @Test
    void countZeroMatches() {
        api.set(UUID.randomUUID(), COINS, 10);
        assertEquals(0, api.count(COINS, c -> c > 1000));
    }

    @Test
    void countAllMatches() {
        api.set(UUID.randomUUID(), COINS, 10);
        api.set(UUID.randomUUID(), COINS, 20);
        api.set(UUID.randomUUID(), COINS, 30);

        assertEquals(3, api.count(COINS, c -> c > 0));
    }

    // ==================== Bulk Updates ====================

    @Test
    void updateAllAffectsEveryone() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 200);
        api.set(p3, COINS, 300);

        int affected = api.updateAll(COINS, c -> c * 2);
        assertEquals(3, affected);
        assertEquals(200, api.get(p1, COINS));
        assertEquals(400, api.get(p2, COINS));
        assertEquals(600, api.get(p3, COINS));
    }

    @Test
    void updateWhereOnlyMatchingPlayers() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        api.set(p1, COINS, 100);
        api.set(p2, COINS, 500);
        api.set(p3, COINS, 1500);

        int affected = api.updateWhere(COINS, c -> c >= 500, c -> c + 100);
        assertEquals(2, affected);
        assertEquals(100, api.get(p1, COINS));
        assertEquals(600, api.get(p2, COINS));
        assertEquals(1600, api.get(p3, COINS));
    }

    @Test
    void updateWhereNoMatches() {
        api.set(UUID.randomUUID(), COINS, 10);
        api.set(UUID.randomUUID(), COINS, 20);

        int affected = api.updateWhere(COINS, c -> c > 1000, c -> c * 2);
        assertEquals(0, affected);
    }

    @Test
    void updateAllReturnsCount() {
        for (int i = 0; i < 5; i++) {
            api.set(UUID.randomUUID(), COINS, i * 100);
        }

        int affected = api.updateAll(COINS, c -> c + 1);
        assertEquals(5, affected);
    }
}
