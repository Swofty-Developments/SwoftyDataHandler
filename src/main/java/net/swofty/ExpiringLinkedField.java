package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

import java.time.Duration;

public class ExpiringLinkedField<K, T> extends LinkedField<K, T> {
    private final Duration defaultTtl;

    private ExpiringLinkedField(String namespace, String key, Codec<T> codec, T defaultValue,
                                 LinkType<K> linkType, Validator<T> validator, Duration defaultTtl) {
        super(namespace, key, codec, defaultValue, linkType, validator);
        this.defaultTtl = defaultTtl;
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public static <K, T> ExpiringLinkedBuilder<K, T> expiringBuilder(String namespace, String key, LinkType<K> linkType) {
        return new ExpiringLinkedBuilder<>(namespace, key, linkType);
    }

    public static class ExpiringLinkedBuilder<K, T> {
        private final String namespace;
        private final String key;
        private final LinkType<K> linkType;
        private Codec<T> codec;
        private T defaultValue;
        private Validator<T> validator;
        private Duration defaultTtl;

        private ExpiringLinkedBuilder(String namespace, String key, LinkType<K> linkType) {
            this.namespace = namespace;
            this.key = key;
            this.linkType = linkType;
        }

        public ExpiringLinkedBuilder<K, T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public ExpiringLinkedBuilder<K, T> defaultValue(T defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public ExpiringLinkedBuilder<K, T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public ExpiringLinkedBuilder<K, T> defaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
            return this;
        }

        public ExpiringLinkedField<K, T> build() {
            return new ExpiringLinkedField<>(namespace, key, codec, defaultValue, linkType, validator, defaultTtl);
        }
    }
}
