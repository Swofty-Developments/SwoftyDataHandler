package net.swofty;

import java.util.Objects;

/** Strongly typed identity for a persisted field. */
public record FieldKey<T>(String namespace, String name) {
    public FieldKey {
        namespace = requirePart(namespace, "namespace");
        name = requirePart(name, "name");
    }

    public static <T> FieldKey<T> of(String namespace, String name) {
        return new FieldKey<>(namespace, name);
    }

    public String serializedName() {
        return namespace + ":" + name;
    }

    private static String requirePart(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(label + " must be non-blank and may not contain ':'");
        }
        return value;
    }
}
