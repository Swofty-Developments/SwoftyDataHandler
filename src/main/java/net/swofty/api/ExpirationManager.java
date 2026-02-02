package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.ExpiringLinkedField;
import net.swofty.event.EventBus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

public class ExpirationManager {
    private final ConcurrentHashMap<String, Instant> expirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public ExpirationManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DataHandler-Expiration");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::checkExpirations, 1, 1, TimeUnit.SECONDS);
    }

    public <T> void setExpiration(UUID player, ExpiringField<T> field, Duration ttl) {
        expirations.put(playerKey(player, field), Instant.now().plus(ttl));
    }

    public <T> boolean isExpired(UUID player, ExpiringField<T> field) {
        Instant expiry = expirations.get(playerKey(player, field));
        return expiry == null || Instant.now().isAfter(expiry);
    }

    public <T> Optional<Duration> getTimeRemaining(UUID player, ExpiringField<T> field) {
        Instant expiry = expirations.get(playerKey(player, field));
        if (expiry == null) return Optional.empty();
        Duration remaining = Duration.between(Instant.now(), expiry);
        return remaining.isNegative() ? Optional.empty() : Optional.of(remaining);
    }

    public <T> void extend(UUID player, ExpiringField<T> field, Duration additional) {
        String key = playerKey(player, field);
        Instant expiry = expirations.get(key);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            throw new IllegalStateException("No active expiration to extend");
        }
        expirations.put(key, expiry.plus(additional));
    }

    public void setLinkedExpiration(String linkTypeName, Object linkKey, DataField<?> field, Duration ttl) {
        expirations.put(linkedKey(linkTypeName, linkKey, field), Instant.now().plus(ttl));
    }

    public boolean isLinkedExpired(String linkTypeName, Object linkKey, DataField<?> field) {
        Instant expiry = expirations.get(linkedKey(linkTypeName, linkKey, field));
        return expiry == null || Instant.now().isAfter(expiry);
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
        expirations.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }
}
