package net.swofty.event;

import java.util.UUID;

@FunctionalInterface
public interface PlayerDataListener<T> {
    void onChanged(UUID player, T oldValue, T newValue);
}
