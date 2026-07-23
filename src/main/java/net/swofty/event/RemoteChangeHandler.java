package net.swofty.event;

import net.swofty.DataField;

import java.util.UUID;

/**
 * Applies changes that arrive from another node (over the distributed event bus) to this
 * node's local caches, so a container that is currently loaded here does not serve a stale
 * value after a peer mutates it. Wired by the API implementation; the event bus itself has
 * no knowledge of how data is cached.
 */
public interface RemoteChangeHandler {
    <T> void onPlayerChange(DataField<T> field, UUID player, T newValue);

    <T> void onLinkedChange(DataField<T> field, String linkTypeName, String linkKey, T newValue);
}
