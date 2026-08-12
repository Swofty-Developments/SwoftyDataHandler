package net.swofty.api;

import java.util.concurrent.CompletableFuture;

/** Stable monitor and in-flight lifecycle state for one cached entity. */
final class EntitySession {
    volatile CompletableFuture<Void> loading;
}
