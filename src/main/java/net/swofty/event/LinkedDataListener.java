package net.swofty.event;

import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface LinkedDataListener<K, T> {
    void onChanged(K linkKey, T oldValue, T newValue, Set<UUID> affectedPlayers);
}
