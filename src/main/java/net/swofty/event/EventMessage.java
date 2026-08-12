package net.swofty.event;

import java.util.Map;

class EventMessage {
    String type;
    String fieldKey;
    String sourceNodeId;
    long version;
    Map<String, Object> data;

    EventMessage() {}

    EventMessage(String type, String fieldKey, String sourceNodeId, long version, Map<String, Object> data) {
        this.type = type;
        this.fieldKey = fieldKey;
        this.sourceNodeId = sourceNodeId;
        this.version = version;
        this.data = data;
    }
}
