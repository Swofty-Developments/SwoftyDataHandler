package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

import java.time.Duration;

public class ExpiringField<T> extends PlayerField<T> {
    private final Duration defaultTtl;

    private ExpiringField(FieldKey<T> key, Codec<T> codec, DefaultValueFactory<? extends T> defaultFactory,
                           Validator<T> validator, Duration defaultTtl) {
        super(key, codec, defaultFactory, validator);
        this.defaultTtl = defaultTtl;
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public static <T> ExpiringBuilder<T> expiringBuilder(String namespace, String key) {
        return new ExpiringBuilder<>(FieldKey.of(namespace, key));
    }

    public static <T> ExpiringBuilder<T> expiringBuilder(FieldKey<T> key) { return new ExpiringBuilder<>(key); }

    public static class ExpiringBuilder<T> {
        private final FieldKey<T> key;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = () -> null;
        private Validator<T> validator;
        private Duration defaultTtl;

        private ExpiringBuilder(FieldKey<T> key) {
            this.key = key;
        }

        public ExpiringBuilder<T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public ExpiringBuilder<T> defaultValue(T defaultValue) {
            this.defaultFactory = DefaultValueFactory.copying(() -> codec, defaultValue);
            return this;
        }

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
            return new ExpiringField<>(key, java.util.Objects.requireNonNull(codec, "codec"), defaultFactory,
                    validator, java.util.Objects.requireNonNull(defaultTtl, "defaultTtl"));
        }
    }
}
