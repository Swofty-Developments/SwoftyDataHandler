package net.swofty.api;

import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.PlayerField;
import net.swofty.data.DataFormat;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class PlayerDataManager {
    private final DataStorage storage;
    private final DataFormat format;
    private final EventBus eventBus;
    private final ConcurrentHashMap<UUID, DataContainer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public PlayerDataManager(DataStorage storage, DataFormat format, EventBus eventBus) {
        this.storage = storage;
        this.format = format;
        this.eventBus = eventBus;
    }

    public Object getLock(UUID player) {
        return locks.computeIfAbsent(player, k -> new Object());
    }

    DataContainer getContainer(UUID player) {
        return cache.computeIfAbsent(player, id -> new DataContainer());
    }

    public <T> T get(UUID player, PlayerField<T> field, ExpirationManager expiration) {
        synchronized (getLock(player)) {
            if (field instanceof ExpiringField<T> exp && expiration.isExpired(player, exp)) {
                return field.defaultValue();
            }
            return getFieldValue(player, field);
        }
    }

    public <T> void set(UUID player, PlayerField<T> field, T value) {
        Validation.validate(field, value);
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            setFieldValue(player, field, value);
            eventBus.firePlayerDataChanged(field, player, oldValue, value);
        }
    }

    public <T> void update(UUID player, PlayerField<T> field, UnaryOperator<T> updater) {
        synchronized (getLock(player)) {
            T oldValue = getFieldValue(player, field);
            T newValue = updater.apply(oldValue);
            Validation.validate(field, newValue);
            setFieldValue(player, field, newValue);
            eventBus.firePlayerDataChanged(field, player, oldValue, newValue);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T getFieldValue(UUID player, DataField<T> field) {
        DataContainer container = getContainer(player);
        if (!container.has(field.fullKey())) {
            byte[] raw = storage.load("players", player.toString());
            container.loadField(field, format, raw);
        }
        return container.get(field);
    }

    <T> void setFieldValue(UUID player, DataField<T> field, T value) {
        DataContainer container = getContainer(player);
        container.set(field, value);
        persist(player);
    }

    void persist(UUID player) {
        DataContainer container = cache.get(player);
        if (container != null) {
            byte[] bytes = container.serialize(format);
            storage.save("players", player.toString(), bytes);
        }
    }

    public List<String> listPlayerIds() {
        return storage.listIds("players");
    }
}
