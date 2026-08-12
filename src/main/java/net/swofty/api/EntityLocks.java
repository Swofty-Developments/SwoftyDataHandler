package net.swofty.api;

/**
 * The monitors entities are locked on, one fixed stripe per hash bucket.
 *
 * <p>A monitor per entity in a map would either grow without bound or have to be evicted, and
 * evicting a monitor is a correctness bug: a thread that read the monitor just before it was
 * removed ends up excluding nobody, because the next thread creates a fresh one. Stripes sidestep
 * that entirely — the identity of the monitor for a key never changes — at the cost of two
 * unrelated entities occasionally sharing one.
 */
final class EntityLocks {
    private static final int STRIPES = 512;

    private final Object[] monitors = new Object[STRIPES];

    EntityLocks() {
        for (int i = 0; i < STRIPES; i++) {
            monitors[i] = new Object();
        }
    }

    Object forKey(Object key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return monitors[hash & (STRIPES - 1)];
    }
}
