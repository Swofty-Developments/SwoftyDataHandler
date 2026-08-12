package net.swofty.api;

import net.swofty.DataField;
import net.swofty.codec.Codec;

/**
 * Minimal DataField implementation used internally to read or write a value by fullKey
 * when the original field reference is not at hand — e.g. reading a player's stored link
 * key with the link type's key codec rather than the declared field codec.
 */
class SimpleFieldRef<T> implements DataField<T> {
    private final net.swofty.FieldKey<T> fieldKey;
    private final Codec<T> codec;

    SimpleFieldRef(String fullKey, Codec<T> codec) {
        int idx = fullKey.indexOf(':');
        this.fieldKey = net.swofty.FieldKey.of(idx >= 0 ? fullKey.substring(0, idx) : "internal",
                idx >= 0 ? fullKey.substring(idx + 1) : fullKey);
        this.codec = codec;
    }

    @Override
    public net.swofty.FieldKey<T> fieldKey() { return fieldKey; }

    @Override
    public Codec<T> codec() {
        return codec;
    }

    @Override
    public T defaultValue() {
        return null;
    }
}
