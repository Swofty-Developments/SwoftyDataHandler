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
 * Leaderboards are index-backed and self-registering: no trackLeaderboard call is needed for a
 * numeric field. The index builds on first rank and is maintained on every subsequent write.
 */
class LeaderboardIndexTest {

    @TempDir
    Path tempDir;

    private static final PlayerField<Integer> COINS = PlayerField.create("game", "coins", Codecs.INT, 0);

    @Test
    void numericLeaderboardWorksWithNoRegistration() {
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        api.set(a, COINS, 300);
        api.set(b, COINS, 100);
        api.set(c, COINS, 200);

        List<LeaderboardEntry<Integer>> top = api.getTop(COINS, 3); // no trackLeaderboard
        assertEquals(List.of(a, c, b), top.stream().map(LeaderboardEntry::playerId).toList());
        assertEquals(List.of(300, 200, 100), top.stream().map(LeaderboardEntry::value).toList());
        assertEquals(1, top.get(0).rank());
        api.shutdown();
    }

    @Test
    void firstRankBackfillsExistingDataThenWritesKeepItCurrent() {
        InMemoryDataStorage storage = new InMemoryDataStorage();

        // Data written before any ranking happened.
        DataAPIImpl seed = new DataAPIImpl(storage);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        seed.set(a, COINS, 5);
        seed.set(b, COINS, 9);
        seed.shutdown();

        DataAPIImpl api = new DataAPIImpl(storage);
        // First rank self-builds the index from the pre-existing data.
        assertEquals(List.of(b, a), api.getTop(COINS, 2).stream().map(LeaderboardEntry::playerId).toList());

        // A later write is reflected without any re-registration.
        api.update(a, COINS, v -> v + 100); // a -> 105, overtakes b
        assertEquals(a, api.getTop(COINS, 1).get(0).playerId());
        api.shutdown();
    }

    @Test
    void paging() {
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        for (int i = 1; i <= 25; i++) {
            api.set(UUID.randomUUID(), COINS, i * 10);
        }
        Page<LeaderboardEntry<Integer>> page = api.getTopPaged(COINS, 1, 10);
        assertEquals(10, page.content().size());
        assertEquals(3, page.totalPages());
        assertEquals(25, page.totalElements());
        assertEquals(250, page.content().get(0).value());
        api.shutdown();
    }

    @Test
    void nonNumericFieldRequiresAScorer() {
        PlayerField<String> NAME = PlayerField.create("game", "name", Codecs.STRING, "");
        DataAPIImpl api = new DataAPIImpl(new InMemoryDataStorage());
        api.set(UUID.randomUUID(), NAME, "abc");

        // Cannot auto-score a String; ranking it without a scorer fails with a clear message.
        assertThrows(IllegalStateException.class, () -> api.getTop(NAME, 5));

        // Registering a scorer makes it rankable.
        UUID longName = UUID.randomUUID();
        api.set(longName, NAME, "a-very-long-name");
        api.trackLeaderboard(NAME, String::length);
        assertEquals(longName, api.getTop(NAME, 5).get(0).playerId());
        api.shutdown();
    }

    @Test
    void storageWithoutAnIndexCannotRank() {
        DataAPIImpl api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
        assertThrows(IllegalStateException.class, () -> api.getTop(COINS, 10));
        assertThrows(IllegalStateException.class, () -> api.trackLeaderboard(COINS, Integer::doubleValue));
        api.shutdown();
    }
}
