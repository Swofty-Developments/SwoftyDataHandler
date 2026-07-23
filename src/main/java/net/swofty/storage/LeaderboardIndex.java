package net.swofty.storage;

import java.util.List;

/**
 * A capability a {@link DataStorage} may implement to maintain sorted indexes (e.g. Redis sorted
 * sets) for leaderboard fields. It is the sole backing for {@code getTop}/{@code getTopPaged}:
 * registering a field with {@code trackLeaderboard} requires an index-capable storage, and ranking
 * reads the requested slice directly ({@code O(log N + page)}) rather than scanning and
 * deserializing every stored entity. A field that was never registered throws instead of scanning.
 */
public interface LeaderboardIndex {

    /** A ranked member: its id and score. */
    record ScoreEntry(String id, double score) {}

    /** Records or updates a member's score in the named leaderboard. */
    void updateScore(String leaderboard, String id, double score);

    /** Removes a member from the named leaderboard. */
    void removeFromLeaderboard(String leaderboard, String id);

    /** Returns the {@code [start, endInclusive]} rank slice, ordered by score. */
    List<ScoreEntry> scoreRange(String leaderboard, int start, int endInclusive, boolean descending);

    /** Number of members in the named leaderboard. */
    long leaderboardSize(String leaderboard);

    default List<ScoreEntry> topScores(String leaderboard, int limit, boolean descending) {
        return scoreRange(leaderboard, 0, limit - 1, descending);
    }
}
