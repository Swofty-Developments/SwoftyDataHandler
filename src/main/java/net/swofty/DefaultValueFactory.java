package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.data.DataWriter;
import net.swofty.data.format.BinaryFormat;

/** Creates a fresh default value for a field. */
@FunctionalInterface
public interface DefaultValueFactory<T> {
    T create();

    static <T> DefaultValueFactory<T> constant(T value) {
        return () -> value;
    }

    /** Builds defaults by codec round-trip, preventing mutable prototypes from being shared. */
    static <T> DefaultValueFactory<T> copying(Codec<T> codec, T prototype) {
        java.util.Objects.requireNonNull(codec, "codec");
        if (prototype == null) return () -> null;
        BinaryFormat format = new BinaryFormat();
        DataWriter writer = format.createWriter();
        codec.write(writer, prototype);
        byte[] encoded = format.toBytes(writer);
        return () -> codec.read(format.createReader(encoded));
    }

    static <T> DefaultValueFactory<T> copying(java.util.function.Supplier<Codec<T>> codec, T prototype) {
        java.util.Objects.requireNonNull(codec, "codec");
        return new DefaultValueFactory<>() {
            private volatile DefaultValueFactory<T> delegate;
            @Override public T create() {
                DefaultValueFactory<T> current = delegate;
                if (current == null) {
                    synchronized (this) {
                        current = delegate;
                        if (current == null) delegate = current = copying(codec.get(), prototype);
                    }
                }
                return current.create();
            }
        };
    }
}
