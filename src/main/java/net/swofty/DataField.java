package net.swofty;

import net.swofty.codec.Codec;

public interface DataField<T> {
    String namespace();
    String key();
    Codec<T> codec();
    T defaultValue();

    default String fullKey() {
        return namespace() + ":" + key();
    }
}
