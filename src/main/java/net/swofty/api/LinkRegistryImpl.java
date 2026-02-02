package net.swofty.api;

import net.swofty.LinkType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LinkRegistryImpl {
    private final ConcurrentHashMap<UUID, Map<String, Object>> playerLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<UUID>> reverseIndex = new ConcurrentHashMap<>();

    public <K> void link(UUID player, LinkType<K> type, K key) {
        playerLinks.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(type.name(), key);
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        reverseIndex.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    @SuppressWarnings("unchecked")
    public <K> K unlink(UUID player, LinkType<K> type) {
        Map<String, Object> links = playerLinks.get(player);
        if (links == null) return null;

        K previousKey = (K) links.remove(type.name());
        if (previousKey == null) return null;

        String ck = LinkedDataManager.compositeKey(type.name(), previousKey);
        Set<UUID> players = reverseIndex.get(ck);
        if (players != null) {
            players.remove(player);
            if (players.isEmpty()) {
                reverseIndex.remove(ck);
            }
        }
        return previousKey;
    }

    @SuppressWarnings("unchecked")
    public <K> K resolve(UUID player, LinkType<K> type) {
        Map<String, Object> links = playerLinks.get(player);
        return links == null ? null : (K) links.get(type.name());
    }

    @SuppressWarnings("unchecked")
    public <K> Optional<K> getLinkKey(UUID player, LinkType<K> type) {
        Map<String, Object> links = playerLinks.get(player);
        if (links == null) return Optional.empty();
        return Optional.ofNullable((K) links.get(type.name()));
    }

    @SuppressWarnings("unchecked")
    Object resolve(UUID player, String linkTypeName) {
        Map<String, Object> links = playerLinks.get(player);
        return links == null ? null : links.get(linkTypeName);
    }

    public <K> Set<UUID> getLinkedPlayers(LinkType<K> type, K key) {
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        return reverseIndex.getOrDefault(ck, Collections.emptySet());
    }
}
