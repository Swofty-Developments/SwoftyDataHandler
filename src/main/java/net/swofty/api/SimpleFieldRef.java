package net.swofty.api;

import net.swofty.DataField;
import net.swofty.codec.Codec;

/**
 * Minimal DataField implementation used internally by TransactionManager
 * to set values by fullKey without needing the original field reference.
 */
class SimpleFieldRef implements DataField<Object> {
    private final String fullKey;

    SimpleFieldRef(String fullKey) {
        this.fullKey = fullKey;
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
    public Codec<Object> codec() {
        return null; // Not used for direct container writes
    }

    @Override
    public Object defaultValue() {
        return null;
    }
}
