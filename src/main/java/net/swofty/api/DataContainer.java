package net.swofty.api;

import net.swofty.DataField;
import net.swofty.data.DataFormat;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;
import net.swofty.data.format.JsonDataWriter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataContainer {
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(DataField<T> field) {
        Object value = data.get(field.fullKey());
        return value == null ? field.defaultValue() : (T) value;
    }

    public <T> void set(DataField<T> field, T value) {
        if (value == null) {
            data.remove(field.fullKey());
        } else {
            data.put(field.fullKey(), value);
        }
    }

    public boolean has(String fullKey) {
        return data.containsKey(fullKey);
    }

    public void loadField(DataField<?> field, DataFormat format, byte[] raw) {
        if (raw == null || data.containsKey(field.fullKey())) return;
        DataReader reader = format.createReader(raw);
        if (reader.hasKey(field.fullKey())) {
            DataReader section = reader.readSection(field.fullKey());
            Object value = field.codec().read(section);
            if (value != null) {
                data.put(field.fullKey(), value);
            }
        }
    }

    public byte[] serialize(DataFormat format) {
        DataWriter writer = format.createWriter();
        if (writer instanceof JsonDataWriter jsonWriter) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                jsonWriter.getData().put(entry.getKey(), entry.getValue());
            }
        }
        return format.toBytes(writer);
    }

    ConcurrentHashMap<String, Object> rawData() {
        return data;
    }
}
