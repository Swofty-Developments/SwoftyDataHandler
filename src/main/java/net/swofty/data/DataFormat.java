package net.swofty.data;

public interface DataFormat {
    DataReader createReader(byte[] data);
    DataWriter createWriter();
    byte[] toBytes(DataWriter writer);
}
