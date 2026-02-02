package net.swofty.data;

import net.swofty.codec.Codec;

import java.util.List;
import java.util.Set;

public interface DataReader {
    int readInt();
    long readLong();
    float readFloat();
    double readDouble();
    boolean readBoolean();
    String readString();
    byte[] readBytes();

    DataReader readSection(String key);
    <T> List<T> readList(Codec<T> elementCodec);
    <T> Set<T> readSet(Codec<T> elementCodec);

    boolean hasKey(String key);
}
