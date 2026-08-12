package net.swofty;

import net.swofty.codec.Codec;

public interface DataField<T> {
    FieldKey<T> fieldKey();

    default String namespace() { return fieldKey().namespace(); }
    default String key() { return fieldKey().name(); }
    Codec<T> codec();
    T defaultValue();

    default String fullKey() {
        return fieldKey().serializedName();
    }
}
