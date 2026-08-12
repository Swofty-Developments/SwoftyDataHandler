package net.swofty.codec;

import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

public interface Codec<T> {
    T read(DataReader reader);
    void write(DataWriter writer, T value);

    static <T> Codec<T> of(java.util.function.Function<DataReader, T> reader,
                           java.util.function.BiConsumer<DataWriter, T> writer) {
        java.util.Objects.requireNonNull(reader, "reader");
        java.util.Objects.requireNonNull(writer, "writer");
        return new Codec<>() {
            @Override public T read(DataReader input) { return reader.apply(input); }
            @Override public void write(DataWriter output, T value) { writer.accept(output, value); }
        };
    }
}
