package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.ExpiringLinkedField;
import net.swofty.event.EventBus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

class ExpirationManager {
    private static final System.Logger LOGGER = System.getLogger(ExpirationManager.class.getName());

    private final ConcurrentHashMap<String, Expiration> expirations = new ConcurrentHashMap<>();
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;

    public ExpirationManager(EventBus eventBus) {
        this.eventBus = eventBus;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DataHandler-Expiration");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::checkExpirations, 1, 1, TimeUnit.SECONDS);
    }

    // The value (and, for linked fields, the members) is captured when the expiration is
    // registered, because by the time it fires the field already reads as its default.
    private record Expiration(Instant expiry, Runnable fire) {}

    public <T> void setExpiration(UUID player, ExpiringField<T> field, Duration ttl, T value) {
        expirations.put(playerKey(player, field), new Expiration(Instant.now().plus(ttl),
                () -> eventBus.fireExpired(field, player, value)));
    }

    public <T> boolean isExpired(UUID player, ExpiringField<T> field) {
        Expiration expiration = expirations.get(playerKey(player, field));
        return expiration == null || Instant.now().isAfter(expiration.expiry());
    }

    public <T> Optional<Duration> getTimeRemaining(UUID player, ExpiringField<T> field) {
        Expiration expiration = expirations.get(playerKey(player, field));
        if (expiration == null) return Optional.empty();
        Duration remaining = Duration.between(Instant.now(), expiration.expiry());
        return remaining.isNegative() ? Optional.empty() : Optional.of(remaining);
    }

    public <T> void extend(UUID player, ExpiringField<T> field, Duration additional) {
        String key = playerKey(player, field);
        Expiration expiration = expirations.get(key);
        if (expiration == null || Instant.now().isAfter(expiration.expiry())) {
            throw new IllegalStateException("No active expiration to extend");
        }
        expirations.put(key, new Expiration(expiration.expiry().plus(additional), expiration.fire()));
    }

    public <K, T> void setLinkedExpiration(ExpiringLinkedField<K, T> field, K linkKey, Duration ttl,
                                           T value, Set<UUID> memberIds) {
        expirations.put(linkedKey(field.linkType().name(), linkKey, field),
                new Expiration(Instant.now().plus(ttl),
                        () -> eventBus.fireLinkedExpired(field, linkKey, value, memberIds)));
    }

    public boolean isLinkedExpired(String linkTypeName, Object linkKey, DataField<?> field) {
        Expiration expiration = expirations.get(linkedKey(linkTypeName, linkKey, field));
        return expiration == null || Instant.now().isAfter(expiration.expiry());
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private String playerKey(UUID player, DataField<?> field) {
        return "player:" + player + ":" + field.fullKey();
    }

    private String linkedKey(String linkTypeName, Object key, DataField<?> field) {
        return "linked:" + linkTypeName + ":" + key + ":" + field.fullKey();
    }

    private void checkExpirations() {
        Instant now = Instant.now();
        for (Map.Entry<String, Expiration> entry : expirations.entrySet()) {
            Expiration expiration = entry.getValue();
            if (!now.isAfter(expiration.expiry())) continue;
            // Drop it first, and only fire if this thread is the one that removed the entry we
            // looked at, so an expiration extended in the meantime is neither lost nor fired.
            if (!expirations.remove(entry.getKey(), expiration)) continue;
            try {
                expiration.fire().run();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Failed to fire expiration for " + entry.getKey(), e);
            }
        }
    }
}
