package net.swofty.api;

import net.swofty.DataField;
import net.swofty.codec.Codec;

/**
 * Minimal DataField implementation used internally to read or write a value by fullKey
 * when the original field reference is not at hand — e.g. reading a player's stored link
 * key with the link type's key codec rather than the declared field codec.
 */
class SimpleFieldRef<T> implements DataField<T> {
    private final String fullKey;
    private final Codec<T> codec;

    SimpleFieldRef(String fullKey, Codec<T> codec) {
        this.fullKey = fullKey;
        this.codec = codec;
    }

    @Override
    public String namespace() {
        int idx = fullKey.indexOf(':');
        return idx >= 0 ? fullKey.substring(0, idx) : "";
    }

    @Override
    public String key() {
        int idx = fullKey.indexOf(':');
        return idx >= 0 ? fullKey.substring(idx + 1) : fullKey;
    }

    @Override
    public String fullKey() {
        return fullKey;
    }

    @Override
    public Codec<T> codec() {
        return codec;
    }

    @Override
    public T defaultValue() {
        return null;
    }
}
