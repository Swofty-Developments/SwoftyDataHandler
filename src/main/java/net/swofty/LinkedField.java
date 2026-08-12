package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

public class LinkedField<K, T> implements DataField<T> {
    private final String namespace;
    private final String key;
    private final Codec<T> codec;
    private final DefaultValueFactory<? extends T> defaultFactory;
    private final LinkType<K> linkType;
    private final Validator<T> validator;

    protected LinkedField(String namespace, String key, Codec<T> codec, T defaultValue,
                           LinkType<K> linkType, Validator<T> validator) {
        this(namespace, key, codec, DefaultValueFactory.constant(defaultValue), linkType, validator);
    }

    protected LinkedField(String namespace, String key, Codec<T> codec,
                          DefaultValueFactory<? extends T> defaultFactory,
                          LinkType<K> linkType, Validator<T> validator) {
        this.namespace = namespace;
        this.key = key;
        this.codec = codec;
        this.defaultFactory = defaultFactory;
        this.linkType = linkType;
        this.validator = validator;
    }

    public static <K, T> LinkedField<K, T> create(String namespace, String key, Codec<T> codec,
                                                    T defaultValue, LinkType<K> linkType) {
        return new LinkedField<>(namespace, key, codec, defaultValue, linkType, null);
    }

    public static <K, T> Builder<K, T> builder(String namespace, String key, LinkType<K> linkType) {
        return new Builder<>(namespace, key, linkType);
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

    public LinkType<K> linkType() {
        return linkType;
    }

    public Validator<T> validator() {
        return validator;
    }

    public static class Builder<K, T> {
        private final String namespace;
        private final String key;
        private final LinkType<K> linkType;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = DefaultValueFactory.constant(null);
        private Validator<T> validator;

        private Builder(String namespace, String key, LinkType<K> linkType) {
            this.namespace = namespace;
            this.key = key;
            this.linkType = linkType;
        }

        public Builder<K, T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<K, T> defaultValue(T defaultValue) {
            this.defaultFactory = DefaultValueFactory.constant(defaultValue);
            return this;
        }

        /**
         * Produces a fresh default on every miss, for mutable defaults that must not be shared.
         * {@link #defaultValue(Object)} keeps returning the one constant it was given.
         */
        public Builder<K, T> defaultFactory(DefaultValueFactory<? extends T> factory) {
            this.defaultFactory = java.util.Objects.requireNonNull(factory, "factory");
            return this;
        }

        public Builder<K, T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public LinkedField<K, T> build() {
            return new LinkedField<>(namespace, key, codec, defaultFactory, linkType, validator);
        }
    }
}
