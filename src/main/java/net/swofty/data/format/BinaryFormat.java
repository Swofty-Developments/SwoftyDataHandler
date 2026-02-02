package net.swofty.data.format;

import net.swofty.data.DataFormat;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;

public class BinaryFormat implements DataFormat {
    @Override
    public DataReader createReader(byte[] data) {
        return new BinaryDataReader(data);
    }

    @Override
    public DataWriter createWriter() {
        return new BinaryDataWriter();
    }

    @Override
    public byte[] toBytes(DataWriter writer) {
        if (!(writer instanceof BinaryDataWriter binaryWriter)) {
            throw new IllegalArgumentException("Expected BinaryDataWriter");
        }
        return binaryWriter.toByteArray();
    }
}
