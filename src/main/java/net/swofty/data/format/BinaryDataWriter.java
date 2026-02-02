package net.swofty.data.format;

import net.swofty.codec.Codec;
import net.swofty.data.DataWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

public class BinaryDataWriter implements DataWriter {
    private final DataOutputStream out;
    private final ByteArrayOutputStream baos;

    public BinaryDataWriter() {
        this.baos = new ByteArrayOutputStream();
        this.out = new DataOutputStream(baos);
    }

    private BinaryDataWriter(DataOutputStream out, ByteArrayOutputStream baos) {
        this.out = out;
        this.baos = baos;
    }

    @Override
    public void writeInt(int value) {
        try {
            out.writeInt(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeLong(long value) {
        try {
            out.writeLong(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeFloat(float value) {
        try {
            out.writeFloat(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeDouble(double value) {
        try {
            out.writeDouble(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBoolean(boolean value) {
        try {
            out.writeBoolean(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeString(String value) {
        try {
            out.writeUTF(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBytes(byte[] bytes) {
        try {
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public DataWriter writeSection(String key) {
        writeString(key);
        return this;
    }

    @Override
    public <T> void writeList(List<T> list, Codec<T> elementCodec) {
        writeInt(list.size());
        for (T element : list) {
            elementCodec.write(this, element);
        }
    }

    @Override
    public <T> void writeSet(Set<T> set, Codec<T> elementCodec) {
        writeList(new ArrayList<>(set), elementCodec);
    }

    public byte[] toByteArray() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }
}
