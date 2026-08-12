package net.swofty.api;

import net.swofty.lock.DistributedLock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The entity locks this thread already holds through the API.
 *
 * <p>A distributed lock is not reentrant — that is the whole point of it, since reentrancy cannot
 * be established across nodes — so a write that transparently takes {@code player:<uuid>} while a
 * transaction on the same player is holding it would wait for itself until the acquisition timed
 * out. Knowing what is already held lets that write ride the lock it is already inside, and lets a
 * write that would need a <em>second</em> entity's lock fail immediately instead of deadlocking
 * against another node doing the same two acquisitions in the other order.
 *
 * <p>An entry is identified by three things, and all three matter. The <b>lock instance</b>, because
 * a key string only means something relative to the lock service it was taken against, and two
 * unrelated locks can hand out the same string to different callers. The <b>key</b>, obviously. And
 * the <b>owner</b> — the API instance that took it — because several {@code DataAPIImpl}s over
 * different namespaces routinely share one lock and therefore generate identical keys for the same
 * player: they hold the same lock, but each has its own cached document, and only the one that took
 * the lock has reread its own copy under it. Riding another owner's lock is safe; skipping the
 * reread that owner never did for you is not.
 *
 * <p>Nothing is recorded for a lockless API. Without a lock there is no cross-node exclusion to
 * inherit, and pretending otherwise let a write on a lock-configured API skip its acquisition
 * entirely.
 */
final class LockScope {
    private static final ThreadLocal<Deque<Entry>> HELD = ThreadLocal.withInitial(ArrayDeque::new);

    private LockScope() {}

    private record Entry(DistributedLock lock, String key, Object owner) {
        boolean matches(DistributedLock lock, String key) {
            return this.lock == lock && this.key.equals(key);
        }
    }

    /** Whether this thread already holds that key on that lock, whoever took it. */
    static boolean holdsKey(DistributedLock lock, String key) {
        for (Entry entry : HELD.get()) {
            if (entry.matches(lock, key)) return true;
        }
        return false;
    }

    /** Whether this thread holds that key on that lock and took it through {@code owner}. */
    static boolean holdsKeyForOwner(DistributedLock lock, String key, Object owner) {
        for (Entry entry : HELD.get()) {
            if (entry.matches(lock, key) && entry.owner() == owner) return true;
        }
        return false;
    }

    /** Whether this thread holds some <em>other</em> entity on the same lock service. */
    static boolean holdsOtherKey(DistributedLock lock, String key) {
        for (Entry entry : HELD.get()) {
            if (entry.lock() == lock && !entry.key().equals(key)) return true;
        }
        return false;
    }

    static String describeHeld(DistributedLock lock) {
        StringBuilder held = new StringBuilder();
        for (Entry entry : HELD.get()) {
            if (entry.lock() != lock) continue;
            if (held.length() > 0) held.append(", ");
            held.append(entry.key());
        }
        return held.toString();
    }

    static void enter(DistributedLock lock, String key, Object owner) {
        HELD.get().push(new Entry(lock, key, owner));
    }

    static void exit(DistributedLock lock, String key, Object owner) {
        Deque<Entry> held = HELD.get();
        for (Iterator<Entry> entries = held.iterator(); entries.hasNext(); ) {
            Entry entry = entries.next();
            if (entry.matches(lock, key) && entry.owner() == owner) {
                entries.remove();
                break;
            }
        }
        if (held.isEmpty()) HELD.remove();
    }
}
