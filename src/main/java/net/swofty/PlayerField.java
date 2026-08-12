package net.swofty;

import net.swofty.codec.Codec;
import net.swofty.validation.Validator;

public class PlayerField<T> implements DataField<T> {
    private final FieldKey<T> fieldKey;
    private final Codec<T> codec;
    private final DefaultValueFactory<? extends T> defaultFactory;
    private final Validator<T> validator;

    protected PlayerField(String namespace, String key, Codec<T> codec, T defaultValue, Validator<T> validator) {
        this(FieldKey.of(namespace, key), codec, DefaultValueFactory.copying(codec, defaultValue), validator);
    }

    protected PlayerField(FieldKey<T> fieldKey, Codec<T> codec,
                          DefaultValueFactory<? extends T> defaultFactory, Validator<T> validator) {
        this.fieldKey = java.util.Objects.requireNonNull(fieldKey, "fieldKey");
        this.codec = codec;
        this.defaultFactory = java.util.Objects.requireNonNull(defaultFactory, "defaultFactory");
        this.validator = validator;
    }

    public static <T> PlayerField<T> create(String namespace, String key, Codec<T> codec, T defaultValue) {
        return new PlayerField<>(namespace, key, codec, defaultValue, null);
    }

    public static <T> Builder<T> builder(String namespace, String key) {
        return new Builder<>(FieldKey.of(namespace, key));
    }

    public static <T> Builder<T> builder(FieldKey<T> key) { return new Builder<>(key); }

    @Override
    public FieldKey<T> fieldKey() { return fieldKey; }

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
        private final FieldKey<T> fieldKey;
        private Codec<T> codec;
        private DefaultValueFactory<? extends T> defaultFactory = () -> null;
        private Validator<T> validator;

        private Builder(FieldKey<T> fieldKey) {
            this.fieldKey = fieldKey;
        }

        public Builder<T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<T> defaultValue(T defaultValue) {
            this.defaultFactory = DefaultValueFactory.copying(() -> codec, defaultValue);
            return this;
        }

        public Builder<T> defaultFactory(DefaultValueFactory<? extends T> factory) {
            this.defaultFactory = java.util.Objects.requireNonNull(factory, "factory");
            return this;
        }

        public Builder<T> validator(Validator<T> validator) {
            this.validator = validator;
            return this;
        }

        public PlayerField<T> build() {
            return new PlayerField<>(fieldKey, java.util.Objects.requireNonNull(codec, "codec"), defaultFactory, validator);
        }
    }
}
