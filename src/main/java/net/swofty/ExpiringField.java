package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

import java.time.Duration;

public class ExpiringField<T> extends PlayerField<T> {
    private final Duration defaultTtl;

    private ExpiringField(String namespace, String key, Codec<T> codec,
                           DefaultValueFactory<? extends T> defaultFactory,
                           Validator<T> validator, Duration defaultTtl) {
        super(namespace, key, codec, defaultFactory, validator);
        this.defaultTtl = defaultTtl;
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public static <T> ExpiringBuilder<T> expiringBuilder(String namespace, String key) {
        return new ExpiringBuilder<>(namespace, key);
    }

    public static class ExpiringBuilder<T> {
        private final String namespace;
        private final String key;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = DefaultValueFactory.constant(null);
        private Validator<T> validator;
        private Duration defaultTtl;

        private ExpiringBuilder(String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
        }

        public ExpiringBuilder<T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public ExpiringBuilder<T> defaultValue(T defaultValue) {
            this.defaultFactory = DefaultValueFactory.constant(defaultValue);
            return this;
        }

        /**
         * Produces a fresh default on every miss, for mutable defaults that must not be shared.
         * {@link #defaultValue(Object)} keeps returning the one constant it was given.
         */
        public ExpiringBuilder<T> defaultFactory(DefaultValueFactory<? extends T> factory) {
            this.defaultFactory = java.util.Objects.requireNonNull(factory, "factory");
            return this;
        }

        public ExpiringBuilder<T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public ExpiringBuilder<T> defaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
            return this;
        }

        public ExpiringField<T> build() {
            return new ExpiringField<>(namespace, key, codec, defaultFactory, validator, defaultTtl);
        }
    }
}
