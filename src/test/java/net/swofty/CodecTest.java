package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.codec.Codecs;
import net.swofty.codec.versioning.VersionedCodec;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;
import net.swofty.data.format.JsonDataReader;
import net.swofty.data.format.JsonDataWriter;
import net.swofty.data.format.JsonFormat;
import net.swofty.data.format.BinaryFormat;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodecTest {

    private final JsonFormat jsonFormat = new JsonFormat();
    private final BinaryFormat binaryFormat = new BinaryFormat();

    private <T> T roundTrip(Codec<T> codec, T value, net.swofty.data.DataFormat format) {
        DataWriter writer = format.createWriter();
        codec.write(writer, value);
        byte[] bytes = format.toBytes(writer);
        DataReader reader = format.createReader(bytes);
        return codec.read(reader);
    }

    // ==================== Primitive Codecs (JSON) ====================

    @Test
    void intCodecJson() {
        assertEquals(42, roundTrip(Codecs.INT, 42, jsonFormat));
    }

    @Test
    void intCodecNegativeJson() {
        assertEquals(-100, roundTrip(Codecs.INT, -100, jsonFormat));
    }

    @Test
    void intCodecZeroJson() {
        assertEquals(0, roundTrip(Codecs.INT, 0, jsonFormat));
    }

    @Test
    void longCodecJson() {
        assertEquals(999999999L, roundTrip(Codecs.LONG, 999999999L, jsonFormat));
    }

    @Test
    void floatCodecJson() {
        assertEquals(3.14f, roundTrip(Codecs.FLOAT, 3.14f, jsonFormat), 0.01f);
    }

    @Test
    void doubleCodecJson() {
        assertEquals(2.718281828, roundTrip(Codecs.DOUBLE, 2.718281828, jsonFormat), 0.0001);
    }

    @Test
    void booleanCodecTrueJson() {
        assertTrue(roundTrip(Codecs.BOOL, true, jsonFormat));
    }

    @Test
    void booleanCodecFalseJson() {
        assertFalse(roundTrip(Codecs.BOOL, false, jsonFormat));
    }

    @Test
    void stringCodecJson() {
        assertEquals("hello world", roundTrip(Codecs.STRING, "hello world", jsonFormat));
    }

    @Test
    void stringCodecEmptyJson() {
        assertEquals("", roundTrip(Codecs.STRING, "", jsonFormat));
    }

    @Test
    void uuidCodecJson() {
        UUID id = UUID.randomUUID();
        assertEquals(id, roundTrip(Codecs.UUID, id, jsonFormat));
    }

    // ==================== Primitive Codecs (Binary) ====================

    @Test
    void intCodecBinary() {
        assertEquals(42, roundTrip(Codecs.INT, 42, binaryFormat));
    }

    @Test
    void longCodecBinary() {
        assertEquals(Long.MAX_VALUE, roundTrip(Codecs.LONG, Long.MAX_VALUE, binaryFormat));
    }

    @Test
    void floatCodecBinary() {
        assertEquals(3.14f, roundTrip(Codecs.FLOAT, 3.14f, binaryFormat), 0.001f);
    }

    @Test
    void doubleCodecBinary() {
        assertEquals(2.718281828, roundTrip(Codecs.DOUBLE, 2.718281828, binaryFormat), 0.0001);
    }

    @Test
    void booleanCodecBinary() {
        assertTrue(roundTrip(Codecs.BOOL, true, binaryFormat));
        assertFalse(roundTrip(Codecs.BOOL, false, binaryFormat));
    }

    @Test
    void stringCodecBinary() {
        assertEquals("test string", roundTrip(Codecs.STRING, "test string", binaryFormat));
    }

    @Test
    void uuidCodecBinary() {
        UUID id = UUID.randomUUID();
        assertEquals(id, roundTrip(Codecs.UUID, id, binaryFormat));
    }

    // ==================== Compound Codecs ====================

    @Test
    void listCodecJson() {
        Codec<List<Integer>> codec = Codecs.list(Codecs.INT);
        List<Integer> input = List.of(1, 2, 3, 4, 5);
        assertEquals(input, roundTrip(codec, input, jsonFormat));
    }

    @Test
    void listCodecEmptyJson() {
        Codec<List<Integer>> codec = Codecs.list(Codecs.INT);
        List<Integer> input = List.of();
        assertEquals(input, roundTrip(codec, input, jsonFormat));
    }

    @Test
    void listCodecBinary() {
        Codec<List<String>> codec = Codecs.list(Codecs.STRING);
        List<String> input = List.of("a", "b", "c");
        assertEquals(input, roundTrip(codec, input, binaryFormat));
    }

    @Test
    void setCodecJson() {
        Codec<Set<String>> codec = Codecs.set(Codecs.STRING);
        Set<String> input = new LinkedHashSet<>(List.of("read", "write", "admin"));

        Set<String> result = roundTrip(codec, input, jsonFormat);
        assertEquals(input, result);
    }

    @Test
    void setCodecBinary() {
        Codec<Set<Integer>> codec = Codecs.set(Codecs.INT);
        Set<Integer> input = new LinkedHashSet<>(List.of(1, 2, 3));

        Set<Integer> result = roundTrip(codec, input, binaryFormat);
        assertEquals(input, result);
    }

    @Test
    void nullableCodecPresentJson() {
        Codec<String> codec = Codecs.nullable(Codecs.STRING);
        assertEquals("hello", roundTrip(codec, "hello", jsonFormat));
    }

    @Test
    void nullableCodecNullJson() {
        Codec<String> codec = Codecs.nullable(Codecs.STRING);
        assertNull(roundTrip(codec, null, jsonFormat));
    }

    @Test
    void nullableCodecPresentBinary() {
        Codec<Integer> codec = Codecs.nullable(Codecs.INT);
        assertEquals(42, roundTrip(codec, 42, binaryFormat));
    }

    @Test
    void nullableCodecNullBinary() {
        Codec<Integer> codec = Codecs.nullable(Codecs.INT);
        assertNull(roundTrip(codec, null, binaryFormat));
    }

    // ==================== VersionedCodec ====================

    record SimpleRecord(String name, int priority, Set<String> perms) {}

    @Test
    void versionedCodecCurrentVersion() {
        VersionedCodec<SimpleRecord> codec = VersionedCodec
                .<SimpleRecord>builder(
                        1,
                        reader -> new SimpleRecord(reader.readString(), 0, Set.of()),
                        (writer, value) -> writer.writeString(value.name())
                )
                .build();

        assertEquals(1, codec.currentVersion());
    }

    @Test
    void versionedCodecWriteAndReadCurrentVersion() {
        VersionedCodec<String> codec = VersionedCodec
                .<String>builder(
                        1,
                        DataReader::readString,
                        DataWriter::writeString
                )
                .build();

        DataWriter writer = binaryFormat.createWriter();
        codec.write(writer, "test");
        byte[] bytes = binaryFormat.toBytes(writer);

        DataReader reader = binaryFormat.createReader(bytes);
        String result = codec.read(reader, 1);
        assertEquals("test", result);
    }

    @Test
    void versionedCodecMigration() {
        // V1 only has name, V2 has name + priority
        VersionedCodec<SimpleRecord> codec = VersionedCodec
                .<SimpleRecord>builder(
                        2,
                        reader -> {
                            String name = reader.readString();
                            int priority = reader.readInt();
                            return new SimpleRecord(name, priority, Set.of());
                        },
                        (writer, value) -> {
                            writer.writeString(value.name());
                            writer.writeInt(value.priority());
                        }
                )
                .legacyReader(1, reader -> {
                    String name = reader.readString();
                    return new SimpleRecord(name, 0, Set.of());
                })
                .migrate(1, 2, old -> new SimpleRecord(old.name(), 0, old.perms()))
                .build();

        // Write as V1 (just a name)
        DataWriter writer = binaryFormat.createWriter();
        writer.writeString("admin");
        byte[] v1Data = binaryFormat.toBytes(writer);

        // Read with migration
        DataReader reader = binaryFormat.createReader(v1Data);
        SimpleRecord result = codec.read(reader, 1);

        assertEquals("admin", result.name());
        assertEquals(0, result.priority());
    }

    @Test
    void versionedCodecMultipleMigrationSteps() {
        VersionedCodec<SimpleRecord> codec = VersionedCodec
                .<SimpleRecord>builder(
                        3,
                        reader -> {
                            String name = reader.readString();
                            int priority = reader.readInt();
                            return new SimpleRecord(name, priority, Set.of());
                        },
                        (writer, value) -> {
                            writer.writeString(value.name());
                            writer.writeInt(value.priority());
                        }
                )
                .legacyReader(1, reader -> {
                    String name = reader.readString();
                    return new SimpleRecord(name, 0, Set.of());
                })
                .legacyReader(2, reader -> {
                    String name = reader.readString();
                    int priority = reader.readInt();
                    return new SimpleRecord(name, priority, Set.of());
                })
                .migrate(1, 2, old -> new SimpleRecord(old.name(), 0, old.perms()))
                .migrate(2, 3, old -> new SimpleRecord(old.name(), old.priority(), Set.of("default")))
                .build();

        // Read from V1 -> should migrate through V2 -> V3
        DataWriter writer = binaryFormat.createWriter();
        writer.writeString("admin");
        byte[] v1Data = binaryFormat.toBytes(writer);

        DataReader reader = binaryFormat.createReader(v1Data);
        SimpleRecord result = codec.read(reader, 1);

        assertEquals("admin", result.name());
        assertEquals(0, result.priority());
        assertEquals(Set.of("default"), result.perms());
    }

    @Test
    void versionedCodecNoReaderThrows() {
        VersionedCodec<String> codec = VersionedCodec
                .<String>builder(2, DataReader::readString, DataWriter::writeString)
                .build();

        DataWriter writer = binaryFormat.createWriter();
        writer.writeString("test");
        byte[] data = binaryFormat.toBytes(writer);

        DataReader reader = binaryFormat.createReader(data);
        assertThrows(IllegalStateException.class, () -> codec.read(reader, 1));
    }
}
