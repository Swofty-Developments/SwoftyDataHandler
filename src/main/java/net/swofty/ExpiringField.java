package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

import java.time.Duration;

public class ExpiringField<T> extends PlayerField<T> {
    private final Duration defaultTtl;

    private ExpiringField(String namespace, String key, Codec<T> codec, T defaultValue,
                           Validator<T> validator, Duration defaultTtl) {
        super(namespace, key, codec, defaultValue, validator);
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
        private T defaultValue;
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
            this.defaultValue = defaultValue;
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
            return new ExpiringField<>(namespace, key, codec, defaultValue, validator, defaultTtl);
        }
    }
}
