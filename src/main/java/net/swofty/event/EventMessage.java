package net.swofty.event;

import java.util.Map;

class EventMessage {
    String type;
    String fieldKey;
    String sourceNodeId;
    Map<String, Object> data;

    EventMessage() {}

    EventMessage(String type, String fieldKey, String sourceNodeId, Map<String, Object> data) {
        this.type = type;
        this.fieldKey = fieldKey;
        this.sourceNodeId = sourceNodeId;
        this.data = data;
    }
}
