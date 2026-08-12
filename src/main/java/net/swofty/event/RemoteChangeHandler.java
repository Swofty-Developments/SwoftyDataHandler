package net.swofty.event;

import net.swofty.DataField;
import net.swofty.LinkType;

import java.util.Set;
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

    /** A player was linked on another node, so this node's link state can converge on it. */
    default <K> void onLinked(LinkType<K> type, UUID player, K linkKey) {}

    /** A player was unlinked on another node. */
    default <K> void onUnlinked(LinkType<K> type, UUID player, K previousKey) {}

    /** Another node flushed a whole document; this node rereads it if it caches the entity. */
    default void onPlayerSnapshot(UUID player, long version) {}

    default void onLinkedSnapshot(String linkTypeName, String linkKey, long version) {}

    /**
     * A shared entity's document was deleted on another node. Returns the players this node had
     * linked to it, which the bus then reports to local link listeners.
     */
    default <K> Set<UUID> onLinkedDeleted(LinkType<K> type, K linkKey) {
        return Set.of();
    }
}
