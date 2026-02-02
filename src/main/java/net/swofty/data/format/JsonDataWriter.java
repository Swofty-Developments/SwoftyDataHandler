package net.swofty.data.format;

import net.swofty.codec.Codec;
import net.swofty.data.DataWriter;

import java.util.*;

public class JsonDataWriter implements DataWriter {
    private final Map<String, Object> data;
    private String currentKey;
    private int autoIndex = 0;

    public JsonDataWriter() {
        this.data = new LinkedHashMap<>();
    }

    private JsonDataWriter(Map<String, Object> data) {
        this.data = data;
    }

    public JsonDataWriter key(String key) {
        this.currentKey = key;
        return this;
    }

    private void put(Object value) {
        if (currentKey != null) {
            data.put(currentKey, value);
            currentKey = null;
        } else {
            data.put("_v" + autoIndex++, value);
        }
    }

    @Override
    public void writeInt(int value) {
        put(value);
    }

    @Override
    public void writeLong(long value) {
        put(value);
    }

    @Override
    public void writeFloat(float value) {
        put((double) value);
    }

    @Override
    public void writeDouble(double value) {
        put(value);
    }

    @Override
    public void writeBoolean(boolean value) {
        put(value);
    }

    @Override
    public void writeString(String value) {
        put(value);
    }

    @Override
    public void writeBytes(byte[] bytes) {
        put(Base64.getEncoder().encodeToString(bytes));
    }

    @Override
    public DataWriter writeSection(String key) {
        Map<String, Object> section = new LinkedHashMap<>();
        data.put(key, section);
        return new JsonDataWriter(section);
    }

    @Override
    public <T> void writeList(List<T> list, Codec<T> elementCodec) {
        List<Object> jsonList = new ArrayList<>();
        for (T element : list) {
            JsonDataWriter elementWriter = new JsonDataWriter();
            elementCodec.write(elementWriter, element);
            if (elementWriter.data.size() == 1 && elementWriter.data.containsKey("_v0")) {
                jsonList.add(elementWriter.data.get("_v0"));
            } else {
                jsonList.add(elementWriter.data);
            }
        }
        put(jsonList);
    }

    @Override
    public <T> void writeSet(Set<T> set, Codec<T> elementCodec) {
        writeList(new ArrayList<>(set), elementCodec);
    }

    public Map<String, Object> getData() {
        return data;
    }
}
