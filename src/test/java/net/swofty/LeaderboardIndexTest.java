package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;
import net.swofty.storage.InMemoryDataStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The indexed leaderboard path (Redis sorted sets in production, an in-memory index here)
 * must return the same ranking as the scan-based fallback, without scanning every player.
 */
class LeaderboardIndexTest {

    @TempDir
    Path tempDir;

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);

    @Test
    void rankingAnUntrackedFieldFailsFast() {
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        // No trackLeaderboard call — ranking must throw instead of silently scanning every player.
        assertThrows(IllegalStateException.class, () -> api.getTop(COINS, 10));
        assertThrows(IllegalStateException.class, () -> api.getTopPaged(COINS, 1, 10));
        api.shutdown();
    }

    @Test
    void trackingOnStorageWithoutAnIndexFailsFast() {
        DataAPIImpl api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
        assertThrows(IllegalStateException.class, () -> api.trackLeaderboard(COINS, Integer::doubleValue));
        api.shutdown();
    }

    @Test
    void indexedGetTopMatchesInsertionRanking() {
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        api.trackLeaderboard(COINS, Integer::doubleValue);

        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        api.set(a, COINS, 300);
        api.set(b, COINS, 100);
        api.set(c, COINS, 200);

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 3);
        assertEquals(List.of(a, c, b), top.stream().map(LeaderboardEntry::playerId).toList());
        assertEquals(List.of(300, 200, 100), top.stream().map(LeaderboardEntry::value).toList());
        assertEquals(1, top.get(0).rank());
        assertEquals(3, top.get(2).rank());
        api.shutdown();
    }

    @Test
    void indexReflectsUpdatesAndPaging() {
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        api.trackLeaderboard(COINS, Integer::doubleValue);

        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        api.set(a, COINS, 10);
        api.set(b, COINS, 20);
        api.update(a, COINS, c -> c + 100); // a jumps to 110, overtaking b

        assertEquals(a, api.getTop(COINS, 1).get(0).playerId());

        Page<LeaderboardEntry<Integer>> page = api.getTopPaged(COINS, 1, 1);
        assertEquals(2, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(a, page.content().get(0).playerId());
        api.shutdown();
    }

    @Test
    void rebuildBackfillsExistingData() {
        InMemoryDataStorage storage = new InMemoryDataStorage();

        // Data written before the leaderboard was tracked.
        DataAPIImpl seed = new DataAPIImpl(storage);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        seed.set(a, COINS, 5);
        seed.set(b, COINS, 9);
        seed.shutdown();

        DataAPIImpl api = new DataAPIImpl(storage);
        api.trackLeaderboard(COINS, Integer::doubleValue);
        api.rebuildLeaderboard(COINS, Integer::doubleValue);

        assertEquals(List.of(b, a), api.getTop(COINS, 2).stream().map(LeaderboardEntry::playerId).toList());
        api.shutdown();
    }
}
