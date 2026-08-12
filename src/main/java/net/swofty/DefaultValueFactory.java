package net.swofty;

/**
 * Creates the value a field reads as when nothing is stored for it.
 *
 * <p>{@code defaultValue(x)} on a field builder hands back the same {@code x} every time, which is
 * what a shared immutable default should do. A mutable default (a list, a map, a record holding
 * one) must not be shared between entities: give the builder a {@code defaultFactory} instead so
 * every miss gets its own instance.
 */
@FunctionalInterface
public interface DefaultValueFactory<T> {
    T create();

    static <T> DefaultValueFactory<T> constant(T value) {
        return () -> value;
    }
}
