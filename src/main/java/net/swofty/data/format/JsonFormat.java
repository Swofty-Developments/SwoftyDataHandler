package net.swofty.data.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.swofty.data.DataFormat;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonFormat implements DataFormat {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    @Override
    public DataReader createReader(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        Map<String, Object> map = GSON.fromJson(json, MAP_TYPE);
        if (map == null) {
            map = new LinkedHashMap<>();
        }
        return new JsonDataReader(map);
    }

    @Override
    public DataWriter createWriter() {
        return new JsonDataWriter();
    }

    @Override
    public byte[] toBytes(DataWriter writer) {
        if (!(writer instanceof JsonDataWriter jsonWriter)) {
            throw new IllegalArgumentException("Expected JsonDataWriter");
        }
        String json = GSON.toJson(jsonWriter.getData());
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
