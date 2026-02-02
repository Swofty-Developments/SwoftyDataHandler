package net.swofty.codec;

import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

public interface Codec<T> {
    T read(DataReader reader);
    void write(DataWriter writer, T value);
}
