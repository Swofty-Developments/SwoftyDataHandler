package net.swofty.data.format;

import net.swofty.codec.Codec;
import net.swofty.data.DataReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public class BinaryDataReader implements DataReader {
    private final DataInputStream in;

    public BinaryDataReader(byte[] data) {
        this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    @Override
    public int readInt() {
        try {
            return in.readInt();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long readLong() {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public float readFloat() {
        try {
            return in.readFloat();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public double readDouble() {
        try {
            return in.readDouble();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean readBoolean() {
        try {
            return in.readBoolean();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String readString() {
        try {
            return in.readUTF();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] readBytes() {
        try {
            int length = in.readInt();
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public DataReader readSection(String key) {
        // Binary format is sequential, section key is read but data follows inline
        return this;
    }

    @Override
    public <T> List<T> readList(Codec<T> elementCodec) {
        int size = readInt();
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(elementCodec.read(this));
        }
        return list;
    }

    @Override
    public <T> Set<T> readSet(Codec<T> elementCodec) {
        return new LinkedHashSet<>(readList(elementCodec));
    }

    @Override
    public boolean hasKey(String key) {
        // Binary format doesn't support key-based lookups
        return false;
    }
}
