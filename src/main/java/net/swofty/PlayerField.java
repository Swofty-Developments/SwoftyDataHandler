package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

public class PlayerField<T> implements DataField<T> {
    private final String namespace;
    private final String key;
    private final Codec<T> codec;
    private final DefaultValueFactory<? extends T> defaultFactory;
    private final Validator<T> validator;

    protected PlayerField(String namespace, String key, Codec<T> codec, T defaultValue, Validator<T> validator) {
        this(namespace, key, codec, DefaultValueFactory.constant(defaultValue), validator);
    }

    protected PlayerField(String namespace, String key, Codec<T> codec,
                          DefaultValueFactory<? extends T> defaultFactory, Validator<T> validator) {
        this.namespace = namespace;
        this.key = key;
        this.codec = codec;
        this.defaultFactory = defaultFactory;
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
        return defaultFactory.create();
    }

    public Validator<T> validator() {
        return validator;
    }

    public static class Builder<T> {
        private final String namespace;
        private final String key;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = DefaultValueFactory.constant(null);
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
            this.defaultFactory = DefaultValueFactory.constant(defaultValue);
            return this;
        }

        /**
         * Produces a fresh default on every miss, for mutable defaults that must not be shared.
         * {@link #defaultValue(Object)} keeps returning the one constant it was given.
         */
        public Builder<T> defaultFactory(DefaultValueFactory<? extends T> factory) {
            this.defaultFactory = java.util.Objects.requireNonNull(factory, "factory");
            return this;
        }

        public Builder<T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public PlayerField<T> build() {
            return new PlayerField<>(namespace, key, codec, defaultFactory, validator);
        }
    }
}
