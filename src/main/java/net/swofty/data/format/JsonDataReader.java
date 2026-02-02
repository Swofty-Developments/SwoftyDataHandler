package net.swofty.data.format;

import net.swofty.codec.Codec;
import net.swofty.data.DataReader;

import java.util.*;

public class JsonDataReader implements DataReader {
    private final Map<String, Object> data;
    private String currentKey;
    private int autoIndex = 0;

    public JsonDataReader(Map<String, Object> data) {
        this.data = data;
    }

    public JsonDataReader key(String key) {
        this.currentKey = key;
        return this;
    }

    private Object get() {
        if (currentKey != null) {
            Object val = data.get(currentKey);
            currentKey = null;
            return val;
        }
        return data.get("_v" + autoIndex++);
    }

    @Override
    public int readInt() {
        Object val = get();
        if (val instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalStateException("Expected int, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public long readLong() {
        Object val = get();
        if (val instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Expected long, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public float readFloat() {
        Object val = get();
        if (val instanceof Number n) {
            return n.floatValue();
        }
        throw new IllegalStateException("Expected float, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public double readDouble() {
        Object val = get();
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("Expected double, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public boolean readBoolean() {
        Object val = get();
        if (val instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("Expected boolean, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public String readString() {
        Object val = get();
        if (val instanceof String s) {
            return s;
        }
        throw new IllegalStateException("Expected string, got " + (val == null ? "null" : val.getClass()));
    }

    @Override
    public byte[] readBytes() {
        String encoded = readString();
        return Base64.getDecoder().decode(encoded);
    }

    @Override
    @SuppressWarnings("unchecked")
    public DataReader readSection(String key) {
        Object val = data.get(key);
        if (val instanceof Map<?, ?> m) {
            return new JsonDataReader((Map<String, Object>) m);
        }
        // Wrap primitive value in a map for section access
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("_v0", val);
        return new JsonDataReader(wrapper);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> readList(Codec<T> elementCodec) {
        Object val = get();
        if (val == null) {
            return new ArrayList<>();
        }
        if (!(val instanceof List<?> list)) {
            throw new IllegalStateException("Expected list, got " + val.getClass());
        }
        List<T> result = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> m) {
                result.add(elementCodec.read(new JsonDataReader((Map<String, Object>) m)));
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_v0", element);
                result.add(elementCodec.read(new JsonDataReader(wrapper)));
            }
        }
        return result;
    }

    @Override
    public <T> Set<T> readSet(Codec<T> elementCodec) {
        return new LinkedHashSet<>(readList(elementCodec));
    }

    @Override
    public boolean hasKey(String key) {
        return data.containsKey(key);
    }
}
