package net.swofty.storage;

import java.util.List;

/**
 * A capability a {@link DataStorage} may implement to maintain sorted indexes (e.g. Redis sorted
 * sets) for leaderboard fields. It is the sole backing for {@code getTop}/{@code getTopPaged}, which
 * read the requested slice directly ({@code O(log N + page)}) rather than scanning and deserializing
 * every stored entity. Indexes are self-registering: built once on first rank ({@link #updateScore})
 * and maintained on every write ({@link #updateScoreIfPresent}), so ranking needs no registration for
 * numeric fields and unranked fields cost nothing. Storage that lacks this capability cannot rank.
 */
public interface LeaderboardIndex {

    /** A ranked member: its id and score. */
    record ScoreEntry(String id, double score) {}

    /** Records or updates a member's score in the named leaderboard, creating it if needed. */
    void updateScore(String leaderboard, String id, double score);

    /**
     * Records a member's score only if the leaderboard already exists. This is how every node
     * maintains a leaderboard once any node has built it, without resurrecting indexes for fields
     * that are never ranked. Must be atomic with respect to the existence check.
     */
    void updateScoreIfPresent(String leaderboard, String id, double score);

    /** Whether the named leaderboard has been built (has any members). */
    boolean leaderboardExists(String leaderboard);

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
