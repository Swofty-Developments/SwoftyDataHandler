package net.swofty.api;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The entity lock keys this thread already holds through the API.
 *
 * <p>A distributed lock is not reentrant — that is the whole point of it, since reentrancy cannot
 * be established across nodes — so a write that transparently takes {@code player:<uuid>} while a
 * transaction on the same player is holding it would wait for itself until the acquisition timed
 * out. Knowing what is already held lets that write ride the lock it is already inside, and lets a
 * write that would need a *second* entity's lock fail immediately instead of deadlocking against
 * another node doing the same two acquisitions in the other order.
 */
final class LockScope {
    private static final ThreadLocal<Deque<String>> HELD = ThreadLocal.withInitial(ArrayDeque::new);

    private LockScope() {}

    static boolean holds(String key) {
        return HELD.get().contains(key);
    }

    static boolean holdsAnything() {
        return !HELD.get().isEmpty();
    }

    static String describeHeld() {
        return String.join(", ", HELD.get());
    }

    static void enter(String key) {
        HELD.get().push(key);
    }

    static void exit(String key) {
        Deque<String> held = HELD.get();
        held.removeFirstOccurrence(key);
        if (held.isEmpty()) HELD.remove();
    }
}
