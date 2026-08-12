package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

import java.time.Duration;

public class ExpiringLinkedField<K, T> extends LinkedField<K, T> {
    private final Duration defaultTtl;

    private ExpiringLinkedField(FieldKey<T> key, Codec<T> codec, DefaultValueFactory<? extends T> defaultFactory,
                                 LinkType<K> linkType, Validator<T> validator, Duration defaultTtl) {
        super(key, codec, defaultFactory, linkType, validator);
        this.defaultTtl = defaultTtl;
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public static <K, T> ExpiringLinkedBuilder<K, T> expiringBuilder(String namespace, String key, LinkType<K> linkType) {
        return new ExpiringLinkedBuilder<>(FieldKey.of(namespace, key), linkType);
    }

    public static <K, T> ExpiringLinkedBuilder<K, T> expiringBuilder(FieldKey<T> key, LinkType<K> linkType) {
        return new ExpiringLinkedBuilder<>(key, linkType);
    }

    public static class ExpiringLinkedBuilder<K, T> {
        private final FieldKey<T> key;
        private final LinkType<K> linkType;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = () -> null;
        private Validator<T> validator;
        private Duration defaultTtl;

        private ExpiringLinkedBuilder(FieldKey<T> key, LinkType<K> linkType) {
            this.key = key;
            this.linkType = linkType;
        }

        public ExpiringLinkedBuilder<K, T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public ExpiringLinkedBuilder<K, T> defaultValue(T defaultValue) {
            this.defaultFactory = DefaultValueFactory.copying(() -> codec, defaultValue);
            return this;
        }

        public ExpiringLinkedBuilder<K, T> defaultFactory(DefaultValueFactory<? extends T> factory) {
            this.defaultFactory = java.util.Objects.requireNonNull(factory, "factory");
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
            return new ExpiringLinkedField<>(key, java.util.Objects.requireNonNull(codec, "codec"), defaultFactory,
                    linkType, validator, java.util.Objects.requireNonNull(defaultTtl, "defaultTtl"));
        }
    }
}
