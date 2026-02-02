package net.swofty.data;

import net.swofty.codec.Codec;

import java.util.List;
import java.util.Set;

public interface DataWriter {
    void writeInt(int value);
    void writeLong(long value);
    void writeFloat(float value);
    void writeDouble(double value);
    void writeBoolean(boolean value);
    void writeString(String value);
    void writeBytes(byte[] bytes);

    DataWriter writeSection(String key);
    <T> void writeList(List<T> list, Codec<T> elementCodec);
    <T> void writeSet(Set<T> set, Codec<T> elementCodec);
}
