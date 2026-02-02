package net.swofty.event;

import net.swofty.LinkType;

import java.util.UUID;

public interface LinkChangeListener<K> {
    void onLinked(UUID player, LinkType<K> type, K linkKey);
    void onUnlinked(UUID player, LinkType<K> type, K previousKey);
}
