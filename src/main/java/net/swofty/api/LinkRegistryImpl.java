package net.swofty.api;

import net.swofty.LinkType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class LinkRegistryImpl {
    private final ConcurrentHashMap<UUID, Map<String, Object>> playerLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<UUID>> reverseIndex = new ConcurrentHashMap<>();
    // Every link type this node has been handed, so a document reread can be reconciled against
    // all of them rather than only against the links this node happens to have cached.
    private final ConcurrentHashMap<String, LinkType<?>> knownTypes = new ConcurrentHashMap<>();

    private volatile LinkKeyLoader keyLoader;

    /**
     * Reads a player's persisted link key. The registry is per-node and in-memory, so a node that
     * never ran {@code link()} for a player (a fresh process, or one the player just moved to) has
     * no entry for them; this is what lets it recover the link from stored data instead.
     */
    interface LinkKeyLoader {
        <K> K load(UUID player, LinkType<K> type);
    }

    void setKeyLoader(LinkKeyLoader keyLoader) {
        this.keyLoader = keyLoader;
    }

    public <K> void link(UUID player, LinkType<K> type, K key) {
        knownTypes.putIfAbsent(type.name(), type);
        playerLinks.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(type.name(), key);
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        reverseIndex.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    @SuppressWarnings("unchecked")
    public <K> K unlink(UUID player, LinkType<K> type) {
        knownTypes.putIfAbsent(type.name(), type);
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

    /**
     * Drops every link this node holds for a player, returning what was cleared as link type to
     * key. Used when the player's own document is deleted: the link key lived on that document, so
     * leaving the reverse index alone would keep a member id in a shared entity that nothing can
     * resolve any more. Like {@link #unlink}, it works off what this node has cached — a node that
     * never saw the link has nothing to clear, and nothing to report.
     */
    @SuppressWarnings("unchecked")
    Map<LinkType<?>, Object> unlinkAll(UUID player) {
        Map<LinkType<?>, Object> cleared = new LinkedHashMap<>();
        for (LinkType<?> raw : knownTypes.values()) {
            LinkType<Object> type = (LinkType<Object>) raw;
            Object previousKey = unlink(player, type);
            if (previousKey != null) {
                cleared.put(type, previousKey);
            }
        }
        playerLinks.remove(player);
        return cleared;
    }

    @SuppressWarnings("unchecked")
    public <K> K resolve(UUID player, LinkType<K> type) {
        knownTypes.putIfAbsent(type.name(), type);
        Map<String, Object> links = playerLinks.get(player);
        K key = links == null ? null : (K) links.get(type.name());
        return key != null ? key : rehydrate(player, type);
    }

    public <K> Optional<K> getLinkKey(UUID player, LinkType<K> type) {
        return Optional.ofNullable(resolve(player, type));
    }

    // Recovers a link this node never saw from the player's stored link-key field, then caches it
    // so later lookups (and the reverse index) behave exactly as if link() had run here.
    private <K> K rehydrate(UUID player, LinkType<K> type) {
        LinkKeyLoader loader = this.keyLoader;
        if (loader == null) return null;
        K key = loader.load(player, type);
        if (key != null) {
            link(player, type, key);
        }
        return key;
    }

    Object resolve(UUID player, String linkTypeName) {
        Map<String, Object> links = playerLinks.get(player);
        return links == null ? null : links.get(linkTypeName);
    }

    public <K> Set<UUID> getLinkedPlayers(LinkType<K> type, K key) {
        knownTypes.putIfAbsent(type.name(), type);
        String ck = LinkedDataManager.compositeKey(type.name(), key);
        return reverseIndex.getOrDefault(ck, Collections.emptySet());
    }

    /**
     * Rebuilds a player's links from what their document now says, after the document was replaced
     * by one another node wrote. Links live on the player's own document, so a reread that silently
     * left this registry alone would keep resolving a coop the player has already left.
     */
    @SuppressWarnings("unchecked")
    void reconcile(UUID player) {
        LinkKeyLoader loader = this.keyLoader;
        if (loader == null) return;
        for (LinkType<?> raw : knownTypes.values()) {
            LinkType<Object> type = (LinkType<Object>) raw;
            Object stored = loader.load(player, type);
            Object cached = resolve(player, type.name());
            if (Objects.equals(stored, cached)) continue;
            if (cached != null) unlink(player, type);
            if (stored != null) link(player, type, stored);
        }
    }
}
