package net.swofty.codec;

import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

import java.time.Instant;
import java.util.*;

public final class Codecs {
    private Codecs() {}

    public static final Codec<Integer> INT = new Codec<>() {
        @Override
        public Integer read(DataReader reader) {
            return reader.readInt();
        }

        @Override
        public void write(DataWriter writer, Integer value) {
            writer.writeInt(value);
        }
    };

    public static final Codec<Long> LONG = new Codec<>() {
        @Override
        public Long read(DataReader reader) {
            return reader.readLong();
        }

        @Override
        public void write(DataWriter writer, Long value) {
            writer.writeLong(value);
        }
    };

    public static final Codec<Float> FLOAT = new Codec<>() {
        @Override
        public Float read(DataReader reader) {
            return reader.readFloat();
        }

        @Override
        public void write(DataWriter writer, Float value) {
            writer.writeFloat(value);
        }
    };

    public static final Codec<Double> DOUBLE = new Codec<>() {
        @Override
        public Double read(DataReader reader) {
            return reader.readDouble();
        }

        @Override
        public void write(DataWriter writer, Double value) {
            writer.writeDouble(value);
        }
    };

    public static final Codec<Boolean> BOOL = new Codec<>() {
        @Override
        public Boolean read(DataReader reader) {
            return reader.readBoolean();
        }

        @Override
        public void write(DataWriter writer, Boolean value) {
            writer.writeBoolean(value);
        }
    };

    public static final Codec<String> STRING = new Codec<>() {
        @Override
        public String read(DataReader reader) {
            return reader.readString();
        }

        @Override
        public void write(DataWriter writer, String value) {
            writer.writeString(value);
        }
    };

    public static final Codec<UUID> UUID = new Codec<>() {
        @Override
        public UUID read(DataReader reader) {
            return java.util.UUID.fromString(reader.readString());
        }

        @Override
        public void write(DataWriter writer, UUID value) {
            writer.writeString(value.toString());
        }
    };

    public static final Codec<Instant> INSTANT = new Codec<>() {
        @Override
        public Instant read(DataReader reader) {
            return Instant.ofEpochMilli(reader.readLong());
        }

        @Override
        public void write(DataWriter writer, Instant value) {
            writer.writeLong(value.toEpochMilli());
        }
    };

    public static <T> Codec<List<T>> list(Codec<T> element) {
        return new Codec<>() {
            @Override
            public List<T> read(DataReader reader) {
                return reader.readList(element);
            }

            @Override
            public void write(DataWriter writer, List<T> value) {
                writer.writeList(value, element);
            }
        };
    }

    public static <T> Codec<Set<T>> set(Codec<T> element) {
        return new Codec<>() {
            @Override
            public Set<T> read(DataReader reader) {
                return reader.readSet(element);
            }

            @Override
            public void write(DataWriter writer, Set<T> value) {
                writer.writeSet(value, element);
            }
        };
    }

    public static <K, V> Codec<Map<K, V>> map(Codec<K> keyCodec, Codec<V> valueCodec) {
        return new Codec<>() {
            @Override
            public Map<K, V> read(DataReader reader) {
                List<K> keys = reader.readList(keyCodec);
                List<V> values = reader.readList(valueCodec);
                Map<K, V> map = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    map.put(keys.get(i), values.get(i));
                }
                return map;
            }

            @Override
            public void write(DataWriter writer, Map<K, V> value) {
                writer.writeList(new ArrayList<>(value.keySet()), keyCodec);
                writer.writeList(new ArrayList<>(value.values()), valueCodec);
            }
        };
    }

    public static <T> Codec<T> nullable(Codec<T> codec) {
        return new Codec<>() {
            @Override
            public T read(DataReader reader) {
                boolean present = reader.readBoolean();
                return present ? codec.read(reader) : null;
            }

            @Override
            public void write(DataWriter writer, T value) {
                writer.writeBoolean(value != null);
                if (value != null) {
                    codec.write(writer, value);
                }
            }
        };
    }
}
