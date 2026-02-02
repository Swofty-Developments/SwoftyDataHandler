package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

public class LinkedField<K, T> implements DataField<T> {
    private final String namespace;
    private final String key;
    private final Codec<T> codec;
    private final T defaultValue;
    private final LinkType<K> linkType;
    private final Validator<T> validator;

    protected LinkedField(String namespace, String key, Codec<T> codec, T defaultValue,
                           LinkType<K> linkType, Validator<T> validator) {
        this.namespace = namespace;
        this.key = key;
        this.codec = codec;
        this.defaultValue = defaultValue;
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
        return defaultValue;
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
        private T defaultValue;
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
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder<K, T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public LinkedField<K, T> build() {
            return new LinkedField<>(namespace, key, codec, defaultValue, linkType, validator);
        }
    }
}
