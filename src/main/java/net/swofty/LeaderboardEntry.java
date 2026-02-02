package net.swofty;

import java.util.UUID;

public record LeaderboardEntry<T>(UUID playerId, T value, int rank) {}
