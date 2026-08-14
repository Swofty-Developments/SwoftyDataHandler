package net.swofty.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.ExpiringLinkedField;
import net.swofty.LinkType;
import net.swofty.LinkedField;
import net.swofty.codec.Codec;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;
import net.swofty.data.format.JsonFormat;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DistributedEventBus extends EventBus {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final String SNAPSHOT_STREAM = "snapshot";
    // Not a stream but a floor over all of them: the version of the whole document this node last
    // read. Every event at or below it is already part of what was read.
    private static final String DOCUMENT_FLOOR = "*document";
    // How many entities one eviction pass may look at. Pinned entities cannot be evicted, and
    // walking past a wall of them on every published and received event turned the ordering state
    // into a per-event scan of the whole map.
    private static final int EVICTION_SCAN_LIMIT = 64;
    private static final String PLAYER_PREFIX = "player:";
    private static final String LINKED_PREFIX = "linked:";

    private final PubSubHandler pubSubHandler;
    private final String nodeId;
    private final JsonFormat serializationFormat = new JsonFormat();

    // How many entities' ordering state to keep for entities this node does NOT cache. Keeping one
    // entry for every entity the whole cluster ever writes is a leak, but the cap must never reach
    // an entity that is loaded here: dropping its state makes the node accept a replayed older
    // event and revert live data, which the next local write then makes durable.
    private static final int MAX_UNCACHED_TRACKED_ENTITIES = 4096;

    private final ConcurrentHashMap<String, DataField<?>> fieldRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkType<?>> linkTypeRegistry = new ConcurrentHashMap<>();

    // entity -> stream -> highest version seen. Field events are ordered per field, not per
    // document: one transaction stamps every field it wrote with the same document version, and a
    // per-document guard would drop all but the first of them.
    private final LinkedHashMap<String, Map<String, Long>> sequences = new LinkedHashMap<>(64, 0.75f, true);

    private volatile RemoteChangeHandler remoteChangeHandler;

    /** Registers the cache-coherency hook. Called by the API implementation after construction. */
    public void setRemoteChangeHandler(RemoteChangeHandler handler) {
        this.remoteChangeHandler = handler;
    }

    public DistributedEventBus(PubSubHandler pubSubHandler) {
        this(pubSubHandler, UUID.randomUUID().toString());
    }

    public DistributedEventBus(PubSubHandler pubSubHandler, String nodeId) {
        this.pubSubHandler = pubSubHandler;
        this.nodeId = nodeId;
        pubSubHandler.subscribe(this::handleMessage);
    }

    // ==================== Auto-register fields on subscribe ====================

    @Override
    public <T> void subscribe(DataField<T> field, PlayerDataListener<T> listener) {
        fieldRegistry.put(field.fullKey(), field);
        super.subscribe(field, listener);
    }

    @Override
    public <K, T> void subscribeLinked(DataField<T> field, LinkedDataListener<K, T> listener) {
        fieldRegistry.put(field.fullKey(), field);
        // Caring about a linked field means caring about the entity it belongs to, so the link type
        // is registered too: without it this node would ignore link, unlink and delete events for
        // the very entity whose fields it is listening to.
        if (field instanceof LinkedField<?, ?> linked) {
            linkTypeRegistry.putIfAbsent(linked.linkType().name(), linked.linkType());
        }
        super.subscribeLinked(field, listener);
    }

    @Override
    public <K> void subscribeLinkChange(LinkType<K> type, LinkChangeListener<K> listener) {
        linkTypeRegistry.put(type.name(), type);
        super.subscribeLinkChange(type, listener);
    }

    @Override
    public <T> void subscribeExpiration(ExpiringField<T> field, ExpirationListener<T> listener) {
        fieldRegistry.put(field.fullKey(), field);
        super.subscribeExpiration(field, listener);
    }

    @Override
    public <K, T> void subscribeLinkedExpiration(ExpiringLinkedField<K, T> field, LinkedExpirationListener<K, T> listener) {
        fieldRegistry.put(field.fullKey(), field);
        super.subscribeLinkedExpiration(field, listener);
    }

    // ==================== Override fire* to publish ====================

    @Override
    public <T> void firePlayerDataChanged(DataField<T> field, UUID player, T oldValue, T newValue) {
        firePlayerDataChanged(field, player, oldValue, newValue, 0L);
    }

    @Override
    public <T> void firePlayerDataChanged(DataField<T> field, UUID player, T oldValue, T newValue, long version) {
        super.firePlayerDataChanged(field, player, oldValue, newValue);
        remember(playerEntity(player), field.fullKey(), version);
        Map<String, Object> data = payload();
        put(data, "player", player.toString());
        put(data, "oldValue", serializeValue(field.codec(), oldValue));
        put(data, "newValue", serializeValue(field.codec(), newValue));
        publish(new EventMessage("PLAYER_DATA_CHANGED", field.fullKey(), nodeId, version, data));
    }

    @Override
    public <K, T> void fireLinkedDataChanged(DataField<T> field, K linkKey, T oldValue, T newValue, Set<UUID> affected) {
        fireLinkedDataChanged(field, linkKey, oldValue, newValue, affected, 0L);
    }

    @Override
    public <K, T> void fireLinkedDataChanged(DataField<T> field, K linkKey, T oldValue, T newValue,
                                              Set<UUID> affected, long version) {
        super.fireLinkedDataChanged(field, linkKey, oldValue, newValue, affected);
        if (field instanceof LinkedField<?, ?> linked) {
            remember(linkedEntity(linked.linkType().name(), linkKey), field.fullKey(), version);
        }
        Map<String, Object> data = payload();
        put(data, "linkKey", serializeLinkKey(field, linkKey));
        put(data, "oldValue", serializeValue(field.codec(), oldValue));
        put(data, "newValue", serializeValue(field.codec(), newValue));
        put(data, "affected", uuidSetToList(affected));
        publish(new EventMessage("LINKED_DATA_CHANGED", field.fullKey(), nodeId, version, data));
    }

    @Override
    public <K> void fireLinked(LinkType<K> type, UUID player, K linkKey) {
        fireLinked(type, player, linkKey, 0L);
    }

    @Override
    public <K> void fireLinked(LinkType<K> type, UUID player, K linkKey, long version) {
        super.fireLinked(type, player, linkKey);
        // Link state is registry state, not a document write: it publishes even on a node that
        // defers its writes, because a peer that never hears about the link cannot resolve it.
        remember(playerEntity(player), linkStream(type.name()), version);
        Map<String, Object> data = payload();
        put(data, "player", player.toString());
        put(data, "linkKey", serializeValue(type.keyCodec(), linkKey));
        publish(new EventMessage("LINKED", type.name(), nodeId, version, data));
    }

    @Override
    public <K> void fireUnlinked(LinkType<K> type, UUID player, K previousKey) {
        fireUnlinked(type, player, previousKey, 0L);
    }

    @Override
    public <K> void fireUnlinked(LinkType<K> type, UUID player, K previousKey, long version) {
        super.fireUnlinked(type, player, previousKey);
        remember(playerEntity(player), linkStream(type.name()), version);
        Map<String, Object> data = payload();
        put(data, "player", player.toString());
        put(data, "previousKey", serializeValue(type.keyCodec(), previousKey));
        publish(new EventMessage("UNLINKED", type.name(), nodeId, version, data));
    }

    @Override
    public <T> void fireExpired(ExpiringField<T> field, UUID playerId, T expiredValue) {
        super.fireExpired(field, playerId, expiredValue);
        Map<String, Object> data = payload();
        put(data, "player", playerId.toString());
        put(data, "expiredValue", serializeValue(field.codec(), expiredValue));
        publish(new EventMessage("EXPIRED", field.fullKey(), nodeId, 0L, data));
    }

    @Override
    public <K, T> void fireLinkedExpired(ExpiringLinkedField<K, T> field, K linkKey, T expiredValue, Set<UUID> memberIds) {
        super.fireLinkedExpired(field, linkKey, expiredValue, memberIds);
        Map<String, Object> data = payload();
        put(data, "linkKey", serializeLinkKey(field, linkKey));
        put(data, "expiredValue", serializeValue(field.codec(), expiredValue));
        put(data, "memberIds", uuidSetToList(memberIds));
        publish(new EventMessage("LINKED_EXPIRED", field.fullKey(), nodeId, 0L, data));
    }

    @Override
    public void firePlayerSnapshotSaved(UUID player, long version) {
        remember(playerEntity(player), SNAPSHOT_STREAM, version);
        Map<String, Object> data = payload();
        put(data, "player", player.toString());
        publish(new EventMessage("PLAYER_SNAPSHOT_SAVED", "", nodeId, version, data));
    }

    @Override
    public void fireLinkedSnapshotSaved(String linkTypeName, Object linkKey, long version) {
        remember(linkedEntity(linkTypeName, linkKey), SNAPSHOT_STREAM, version);
        Map<String, Object> data = payload();
        put(data, "linkType", linkTypeName);
        put(data, "linkKey", linkKey.toString());
        publish(new EventMessage("LINKED_SNAPSHOT_SAVED", "", nodeId, version, data));
    }

    @Override
    public <K> void fireLinkDeleted(LinkType<K> type, K linkKey) {
        forgetLinked(type.name(), linkKey);
        Map<String, Object> data = payload();
        put(data, "linkKey", serializeValue(type.keyCodec(), linkKey));
        publish(new EventMessage("LINK_DELETED", type.name(), nodeId, 0L, data));
    }

    // A deletion is not an edit at some version, so it publishes unversioned and is delivered
    // unconditionally: there is no later state of the document for it to be stale against, and a
    // peer that dropped the event would keep serving a document that no longer exists. The cleared
    // links travel no further than this node — every peer derives its own from its own registry,
    // which is the only thing that knows what that peer had cached.
    @Override
    public void firePlayerDeleted(UUID player, Map<LinkType<?>, Object> clearedLinks) {
        super.firePlayerDeleted(player, clearedLinks);
        forgetPlayer(player);
        Map<String, Object> data = payload();
        put(data, "player", player.toString());
        publish(new EventMessage("PLAYER_DELETED", "", nodeId, 0L, data));
    }

    // A null-valued field is a legitimate state (an unset nullable field, a cleared link), so
    // payloads omit it rather than blowing up the write that triggered the event. The receiving
    // side reads a missing entry back as null.
    private static Map<String, Object> payload() {
        return new HashMap<>();
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    // ==================== Pub/Sub ====================

    private void publish(EventMessage message) {
        pubSubHandler.publish(GSON.toJson(message));
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String json) {
        EventMessage msg;
        try {
            msg = GSON.fromJson(json, EventMessage.class);
        } catch (Exception e) {
            return;
        }
        if (nodeId.equals(msg.sourceNodeId)) return;

        switch (msg.type) {
            case "PLAYER_DATA_CHANGED" -> handlePlayerDataChanged(msg);
            case "LINKED_DATA_CHANGED" -> handleLinkedDataChanged(msg);
            case "LINKED" -> handleLinked(msg);
            case "UNLINKED" -> handleUnlinked(msg);
            case "EXPIRED" -> handleExpired(msg);
            case "LINKED_EXPIRED" -> handleLinkedExpired(msg);
            case "PLAYER_SNAPSHOT_SAVED" -> handlePlayerSnapshot(msg);
            case "LINKED_SNAPSHOT_SAVED" -> handleLinkedSnapshot(msg);
            case "LINK_DELETED" -> handleLinkDeleted(msg);
            case "PLAYER_DELETED" -> handlePlayerDeleted(msg);
        }
    }

    private void handlePlayerSnapshot(EventMessage msg) {
        UUID player = UUID.fromString((String) msg.data.get("player"));
        if (stale(playerEntity(player), SNAPSHOT_STREAM, msg.version)) return;
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null) handler.onPlayerSnapshot(player, msg.version);
    }

    private void handleLinkedSnapshot(EventMessage msg) {
        String linkType = (String) msg.data.get("linkType");
        String linkKey = (String) msg.data.get("linkKey");
        if (stale(linkedEntity(linkType, linkKey), SNAPSHOT_STREAM, msg.version)) return;
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null) handler.onLinkedSnapshot(linkType, linkKey, msg.version);
    }

    @SuppressWarnings("unchecked")
    private void handlePlayerDataChanged(EventMessage msg) {
        DataField<Object> field = (DataField<Object>) fieldRegistry.get(msg.fieldKey);
        if (field == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        if (stale(playerEntity(player), msg.fieldKey, msg.version)) return;
        Object oldValue = deserializeValue(field.codec(), msg.data.get("oldValue"));
        Object newValue = deserializeValue(field.codec(), msg.data.get("newValue"));
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null) {
            handler.onPlayerChange(field, player, newValue);
        }
        super.firePlayerDataChanged(field, player, oldValue, newValue);
    }

    @SuppressWarnings("unchecked")
    private void handleLinkedDataChanged(EventMessage msg) {
        DataField<Object> field = (DataField<Object>) fieldRegistry.get(msg.fieldKey);
        if (!(field instanceof LinkedField<?, ?> linkedField)) return;
        Object linkKey = deserializeLinkKey(linkedField, msg.data.get("linkKey"));
        if (linkKey == null) return;
        if (stale(linkedEntity(linkedField.linkType().name(), linkKey), msg.fieldKey, msg.version)) return;
        Object oldValue = deserializeValue(field.codec(), msg.data.get("oldValue"));
        Object newValue = deserializeValue(field.codec(), msg.data.get("newValue"));
        Set<UUID> affected = listToUuidSet(msg.data.get("affected"));
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null) {
            handler.onLinkedChange(field, linkedField.linkType().name(), linkKey.toString(), newValue);
        }
        super.fireLinkedDataChanged(field, linkKey, oldValue, newValue, affected);
    }

    /**
     * Rejects an event that has already been superseded on the same stream.
     *
     * <p>A stream is one field of one entity, not the whole document: a transaction writes several
     * fields at one document version, and two nodes can legitimately write different fields at
     * versions that arrive out of order. Version 0 means the publisher had no durable version to
     * order by (a deferred write), so it is always delivered.
     */
    private boolean stale(String entity, String stream, long version) {
        if (version <= 0) return false;
        synchronized (sequences) {
            Map<String, Long> streams = sequences.computeIfAbsent(entity, ignored -> new HashMap<>());
            Long floor = streams.get(DOCUMENT_FLOOR);
            if (floor != null && version <= floor) return true;
            Long previous = streams.get(stream);
            if (previous != null && version <= previous) return true;
            streams.put(stream, version);
            evictUncachedEntities();
            return false;
        }
    }

    @Override
    public void rememberPlayerDocument(UUID player, long version) {
        remember(playerEntity(player), DOCUMENT_FLOOR, version);
    }

    @Override
    public void rememberLinkedDocument(String linkTypeName, Object linkKey, long version) {
        remember(linkedEntity(linkTypeName, linkKey), DOCUMENT_FLOOR, version);
    }

    private void remember(String entity, String stream, long version) {
        if (version <= 0) return;
        synchronized (sequences) {
            sequences.computeIfAbsent(entity, ignored -> new HashMap<>()).merge(stream, version, Math::max);
            evictUncachedEntities();
        }
    }

    // Walks from the least recently touched entity, skipping the ones this node caches. Those are
    // pinned for as long as they are loaded, so the map can exceed the cap - by the number of
    // entities this node is actually serving, which is bounded by the node itself.
    //
    // The walk is capped, because a node serving more entities than the cap would otherwise step
    // over every one of them on every single event, on the pub/sub receive path, holding this
    // monitor. Whatever pinned entities it did step over are moved to the young end afterwards, so
    // the next pass starts on candidates that might actually be evictable instead of the same wall.
    private void evictUncachedEntities() {
        int overflow = sequences.size() - MAX_UNCACHED_TRACKED_ENTITIES;
        if (overflow <= 0) return;
        List<String> steppedOver = null;
        int examined = 0;
        Iterator<Map.Entry<String, Map<String, Long>>> entities = sequences.entrySet().iterator();
        while (overflow > 0 && examined++ < EVICTION_SCAN_LIMIT && entities.hasNext()) {
            String entity = entities.next().getKey();
            if (cachedHere(entity)) {
                if (steppedOver == null) steppedOver = new ArrayList<>();
                steppedOver.add(entity);
                continue;
            }
            entities.remove();
            overflow--;
        }
        if (steppedOver == null) return;
        for (String entity : steppedOver) {
            sequences.get(entity);
        }
    }

    private boolean cachedHere(String entity) {
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler == null) return false;
        if (entity.startsWith(PLAYER_PREFIX)) {
            try {
                return handler.isPlayerCached(UUID.fromString(entity.substring(PLAYER_PREFIX.length())));
            } catch (IllegalArgumentException notAPlayerId) {
                return false;
            }
        }
        if (entity.startsWith(LINKED_PREFIX)) {
            String rest = entity.substring(LINKED_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon < 0) return false;
            return handler.isLinkedCached(rest.substring(0, colon), rest.substring(colon + 1));
        }
        return false;
    }

    @Override
    public void forgetPlayer(UUID player) {
        synchronized (sequences) {
            sequences.remove(playerEntity(player));
        }
    }

    @Override
    public void forgetLinked(String linkTypeName, Object linkKey) {
        synchronized (sequences) {
            sequences.remove(linkedEntity(linkTypeName, linkKey));
        }
    }

    private static String playerEntity(UUID player) {
        return PLAYER_PREFIX + player;
    }

    private static String linkedEntity(String linkTypeName, Object linkKey) {
        return LINKED_PREFIX + linkTypeName + ":" + linkKey;
    }

    private static String linkStream(String linkTypeName) {
        return "link:" + linkTypeName;
    }

    int trackedEntities() {
        synchronized (sequences) {
            return sequences.size();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLinked(EventMessage msg) {
        LinkType<Object> type = (LinkType<Object>) linkTypeRegistry.get(msg.fieldKey);
        if (type == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        if (stale(playerEntity(player), linkStream(msg.fieldKey), msg.version)) return;
        Object linkKey = deserializeValue(type.keyCodec(), msg.data.get("linkKey"));
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null && linkKey != null) {
            handler.onLinked(type, player, linkKey);
        }
        super.fireLinked(type, player, linkKey);
    }

    @SuppressWarnings("unchecked")
    private void handleUnlinked(EventMessage msg) {
        LinkType<Object> type = (LinkType<Object>) linkTypeRegistry.get(msg.fieldKey);
        if (type == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        if (stale(playerEntity(player), linkStream(msg.fieldKey), msg.version)) return;
        Object previousKey = deserializeValue(type.keyCodec(), msg.data.get("previousKey"));
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler != null) {
            handler.onUnlinked(type, player, previousKey);
        }
        super.fireUnlinked(type, player, previousKey);
    }

    @SuppressWarnings("unchecked")
    private void handleLinkDeleted(EventMessage msg) {
        LinkType<Object> type = (LinkType<Object>) linkTypeRegistry.get(msg.fieldKey);
        if (type == null) return;
        Object linkKey = deserializeValue(type.keyCodec(), msg.data.get("linkKey"));
        if (linkKey == null) return;
        forgetLinked(type.name(), linkKey);
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler == null) return;
        for (UUID player : handler.onLinkedDeleted(type, linkKey)) {
            super.fireUnlinked(type, player, linkKey);
        }
    }

    private void handlePlayerDeleted(EventMessage msg) {
        UUID player = UUID.fromString((String) msg.data.get("player"));
        forgetPlayer(player);
        RemoteChangeHandler handler = remoteChangeHandler;
        if (handler == null) return;
        // The handler clears this node's own link state and reports what it cleared, which is what
        // local link listeners are then told about; there is no unlink message to wait for.
        super.firePlayerDeleted(player, handler.onPlayerDeleted(player));
    }

    @SuppressWarnings("unchecked")
    private void handleExpired(EventMessage msg) {
        DataField<?> raw = fieldRegistry.get(msg.fieldKey);
        if (!(raw instanceof ExpiringField)) return;
        ExpiringField<Object> field = (ExpiringField<Object>) raw;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        Object expiredValue = deserializeValue(field.codec(), msg.data.get("expiredValue"));
        super.fireExpired(field, player, expiredValue);
    }

    @SuppressWarnings("unchecked")
    private void handleLinkedExpired(EventMessage msg) {
        DataField<?> raw = fieldRegistry.get(msg.fieldKey);
        if (!(raw instanceof ExpiringLinkedField)) return;
        ExpiringLinkedField<Object, Object> field = (ExpiringLinkedField<Object, Object>) raw;
        Object linkKey = deserializeLinkKey(field, msg.data.get("linkKey"));
        if (linkKey == null) return;
        Object expiredValue = deserializeValue(field.codec(), msg.data.get("expiredValue"));
        Set<UUID> memberIds = listToUuidSet(msg.data.get("memberIds"));
        super.fireLinkedExpired(field, linkKey, expiredValue, memberIds);
    }

    // ==================== Serialization ====================

    /**
     * Encodes a link key with its {@link LinkType}'s key codec so the receiving node can rebuild the
     * typed key rather than handing listeners a bare string. The cache-coherency path still keys
     * containers by {@code toString()}, which round-trips through the codec unchanged.
     */
    private String serializeLinkKey(DataField<?> field, Object linkKey) {
        Codec<Object> keyCodec = linkKeyCodec(field);
        if (keyCodec == null || linkKey == null) return null;
        return serializeValue(keyCodec, linkKey);
    }

    private Object deserializeLinkKey(DataField<?> field, Object serialized) {
        Codec<Object> keyCodec = linkKeyCodec(field);
        if (keyCodec == null) return null;
        return deserializeValue(keyCodec, serialized);
    }

    @SuppressWarnings("unchecked")
    private Codec<Object> linkKeyCodec(DataField<?> field) {
        return field instanceof LinkedField<?, ?> linked ? (Codec<Object>) linked.linkType().keyCodec() : null;
    }

    private <T> String serializeValue(Codec<T> codec, T value) {
        if (value == null) return null;
        DataWriter writer = serializationFormat.createWriter();
        codec.write(writer, value);
        byte[] bytes = serializationFormat.toBytes(writer);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @SuppressWarnings("unchecked")
    private <T> T deserializeValue(Codec<T> codec, Object serialized) {
        if (serialized == null) return null;
        String base64 = (String) serialized;
        byte[] bytes = Base64.getDecoder().decode(base64);
        DataReader reader = serializationFormat.createReader(bytes);
        return codec.read(reader);
    }

    private List<String> uuidSetToList(Set<UUID> uuids) {
        if (uuids == null) return List.of();
        return uuids.stream().map(UUID::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> listToUuidSet(Object raw) {
        if (raw == null) return Set.of();
        List<String> list = (List<String>) raw;
        Set<UUID> result = new HashSet<>();
        for (String s : list) {
            result.add(UUID.fromString(s));
        }
        return result;
    }

    // ==================== Lifecycle ====================

    public void shutdown() {
        pubSubHandler.shutdown();
    }
}
