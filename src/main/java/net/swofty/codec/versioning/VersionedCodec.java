package net.swofty.codec.versioning;

import net.swofty.codec.Codec;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public class VersionedCodec<T> implements Codec<T> {
    private final int currentVersion;
    private final Function<DataReader, T> currentReader;
    private final WriterFunc<T> currentWriter;
    private final Map<Integer, Function<DataReader, T>> legacyReaders;
    private final Map<Integer, Map<Integer, UnaryOperator<T>>> migrations;

    private VersionedCodec(int currentVersion,
                            Function<DataReader, T> currentReader,
                            WriterFunc<T> currentWriter,
                            Map<Integer, Function<DataReader, T>> legacyReaders,
                            Map<Integer, Map<Integer, UnaryOperator<T>>> migrations) {
        this.currentVersion = currentVersion;
        this.currentReader = currentReader;
        this.currentWriter = currentWriter;
        this.legacyReaders = legacyReaders;
        this.migrations = migrations;
    }

    public int currentVersion() {
        return currentVersion;
    }

    @Override
    public T read(DataReader reader) {
        int version = reader.hasKey("_version") ? reader.readSection("_version").readInt() : currentVersion;
        return read(reader, version);
    }

    public T read(DataReader reader, int dataVersion) {
        T value;
        if (dataVersion == currentVersion) {
            value = currentReader.apply(reader);
        } else {
            Function<DataReader, T> legacyReader = legacyReaders.get(dataVersion);
            if (legacyReader == null) {
                throw new IllegalStateException("No reader for version " + dataVersion);
            }
            value = legacyReader.apply(reader);
        }

        // Apply migrations from dataVersion to currentVersion
        if (dataVersion < currentVersion) {
            for (int v = dataVersion; v < currentVersion; v++) {
                Map<Integer, UnaryOperator<T>> fromMigrations = migrations.get(v);
                if (fromMigrations != null) {
                    UnaryOperator<T> migration = fromMigrations.get(v + 1);
                    if (migration != null) {
                        value = migration.apply(value);
                    }
                }
            }
        }

        return value;
    }

    @Override
    public void write(DataWriter writer, T value) {
        currentWriter.write(writer, value);
    }

    public static <T> Builder<T> builder(int currentVersion,
                                          Function<DataReader, T> reader,
                                          WriterFunc<T> writer) {
        return new Builder<>(currentVersion, reader, writer);
    }

    @FunctionalInterface
    public interface WriterFunc<T> {
        void write(DataWriter writer, T value);
    }

    public static class Builder<T> {
        private final int currentVersion;
        private final Function<DataReader, T> currentReader;
        private final WriterFunc<T> currentWriter;
        private final Map<Integer, Function<DataReader, T>> legacyReaders = new HashMap<>();
        private final Map<Integer, Map<Integer, UnaryOperator<T>>> migrations = new HashMap<>();

        private Builder(int currentVersion, Function<DataReader, T> reader, WriterFunc<T> writer) {
            this.currentVersion = currentVersion;
            this.currentReader = reader;
            this.currentWriter = writer;
        }

        public Builder<T> legacyReader(int version, Function<DataReader, T> reader) {
            legacyReaders.put(version, reader);
            return this;
        }

        public Builder<T> migrate(int fromVersion, int toVersion, UnaryOperator<T> migration) {
            migrations.computeIfAbsent(fromVersion, k -> new HashMap<>()).put(toVersion, migration);
            return this;
        }

        public VersionedCodec<T> build() {
            return new VersionedCodec<>(currentVersion, currentReader, currentWriter, legacyReaders, migrations);
        }
    }
}
