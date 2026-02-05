package net.swofty.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.swofty.DataField;
import net.swofty.ExpiringField;
import net.swofty.ExpiringLinkedField;
import net.swofty.LinkType;
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

    private final PubSubHandler pubSubHandler;
    private final String nodeId;
    private final JsonFormat serializationFormat = new JsonFormat();

    private final ConcurrentHashMap<String, DataField<?>> fieldRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkType<?>> linkTypeRegistry = new ConcurrentHashMap<>();

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
        super.firePlayerDataChanged(field, player, oldValue, newValue);
        publish(new EventMessage("PLAYER_DATA_CHANGED", field.fullKey(), nodeId, Map.of(
                "player", player.toString(),
                "oldValue", serializeValue(field.codec(), oldValue),
                "newValue", serializeValue(field.codec(), newValue)
        )));
    }

    @Override
    public <K, T> void fireLinkedDataChanged(DataField<T> field, K linkKey, T oldValue, T newValue, Set<UUID> affected) {
        super.fireLinkedDataChanged(field, linkKey, oldValue, newValue, affected);
        publish(new EventMessage("LINKED_DATA_CHANGED", field.fullKey(), nodeId, Map.of(
                "linkKey", linkKey.toString(),
                "oldValue", serializeValue(field.codec(), oldValue),
                "newValue", serializeValue(field.codec(), newValue),
                "affected", uuidSetToList(affected)
        )));
    }

    @Override
    public <K> void fireLinked(LinkType<K> type, UUID player, K linkKey) {
        super.fireLinked(type, player, linkKey);
        publish(new EventMessage("LINKED", type.name(), nodeId, Map.of(
                "player", player.toString(),
                "linkKey", serializeValue(type.keyCodec(), linkKey)
        )));
    }

    @Override
    public <K> void fireUnlinked(LinkType<K> type, UUID player, K previousKey) {
        super.fireUnlinked(type, player, previousKey);
        publish(new EventMessage("UNLINKED", type.name(), nodeId, Map.of(
                "player", player.toString(),
                "previousKey", serializeValue(type.keyCodec(), previousKey)
        )));
    }

    @Override
    public <T> void fireExpired(ExpiringField<T> field, UUID playerId, T expiredValue) {
        super.fireExpired(field, playerId, expiredValue);
        publish(new EventMessage("EXPIRED", field.fullKey(), nodeId, Map.of(
                "player", playerId.toString(),
                "expiredValue", serializeValue(field.codec(), expiredValue)
        )));
    }

    @Override
    public <K, T> void fireLinkedExpired(ExpiringLinkedField<K, T> field, K linkKey, T expiredValue, Set<UUID> memberIds) {
        super.fireLinkedExpired(field, linkKey, expiredValue, memberIds);
        publish(new EventMessage("LINKED_EXPIRED", field.fullKey(), nodeId, Map.of(
                "linkKey", linkKey.toString(),
                "expiredValue", serializeValue(field.codec(), expiredValue),
                "memberIds", uuidSetToList(memberIds)
        )));
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
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePlayerDataChanged(EventMessage msg) {
        DataField<Object> field = (DataField<Object>) fieldRegistry.get(msg.fieldKey);
        if (field == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        Object oldValue = deserializeValue(field.codec(), msg.data.get("oldValue"));
        Object newValue = deserializeValue(field.codec(), msg.data.get("newValue"));
        super.firePlayerDataChanged(field, player, oldValue, newValue);
    }

    @SuppressWarnings("unchecked")
    private void handleLinkedDataChanged(EventMessage msg) {
        DataField<Object> field = (DataField<Object>) fieldRegistry.get(msg.fieldKey);
        if (field == null) return;
        String linkKey = (String) msg.data.get("linkKey");
        Object oldValue = deserializeValue(field.codec(), msg.data.get("oldValue"));
        Object newValue = deserializeValue(field.codec(), msg.data.get("newValue"));
        Set<UUID> affected = listToUuidSet(msg.data.get("affected"));
        super.fireLinkedDataChanged(field, linkKey, oldValue, newValue, affected);
    }

    @SuppressWarnings("unchecked")
    private void handleLinked(EventMessage msg) {
        LinkType<Object> type = (LinkType<Object>) linkTypeRegistry.get(msg.fieldKey);
        if (type == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        Object linkKey = deserializeValue(type.keyCodec(), msg.data.get("linkKey"));
        super.fireLinked(type, player, linkKey);
    }

    @SuppressWarnings("unchecked")
    private void handleUnlinked(EventMessage msg) {
        LinkType<Object> type = (LinkType<Object>) linkTypeRegistry.get(msg.fieldKey);
        if (type == null) return;
        UUID player = UUID.fromString((String) msg.data.get("player"));
        Object previousKey = deserializeValue(type.keyCodec(), msg.data.get("previousKey"));
        super.fireUnlinked(type, player, previousKey);
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
        String linkKey = (String) msg.data.get("linkKey");
        Object expiredValue = deserializeValue(field.codec(), msg.data.get("expiredValue"));
        Set<UUID> memberIds = listToUuidSet(msg.data.get("memberIds"));
        super.fireLinkedExpired(field, linkKey, expiredValue, memberIds);
    }

    // ==================== Serialization ====================

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
