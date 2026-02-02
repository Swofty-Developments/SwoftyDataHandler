package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

public class PlayerField<T> implements DataField<T> {
    private final String namespace;
    private final String key;
    private final Codec<T> codec;
    private final T defaultValue;
    private final Validator<T> validator;

    protected PlayerField(String namespace, String key, Codec<T> codec, T defaultValue, Validator<T> validator) {
        this.namespace = namespace;
        this.key = key;
        this.codec = codec;
        this.defaultValue = defaultValue;
        this.validator = validator;
    }

    public static <T> PlayerField<T> create(String namespace, String key, Codec<T> codec, T defaultValue) {
        return new PlayerField<>(namespace, key, codec, defaultValue, null);
    }

    public static <T> Builder<T> builder(String namespace, String key) {
        return new Builder<>(namespace, key);
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Codec<T> codec() {
        return codec;
    }

    @Override
    public T defaultValue() {
        return defaultValue;
    }

    public Validator<T> validator() {
        return validator;
    }

    public static class Builder<T> {
        private final String namespace;
        private final String key;
        private Codec<T> codec;
        private T defaultValue;
        private Validator<T> validator;

        private Builder(String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
        }

        public Builder<T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<T> defaultValue(T defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder<T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public PlayerField<T> build() {
            return new PlayerField<>(namespace, key, codec, defaultValue, validator);
        }
    }
}
