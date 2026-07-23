package net.swofty.storage;

import java.util.List;

/**
 * An optional capability a {@link DataStorage} may implement to maintain sorted indexes
 * (e.g. Redis sorted sets) for leaderboard fields. When present, {@code getTop}/{@code getTopPaged}
 * for a tracked field read the ranked slice directly instead of scanning and deserializing every
 * stored entity, turning an O(N) query into O(log N + page). When absent, the API falls back to
 * the scan-based implementation, so this is purely additive.
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
