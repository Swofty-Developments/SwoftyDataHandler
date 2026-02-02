package net.swofty;

import net.swofty.codec.Codec;

public class LinkType<K> {
    private final String name;
    private final Codec<K> keyCodec;
    private final PlayerField<K> playerField;

    private LinkType(String name, Codec<K> keyCodec, PlayerField<K> playerField) {
        this.name = name;
        this.keyCodec = keyCodec;
        this.playerField = playerField;
    }

    public static <K> LinkType<K> create(String name, Codec<K> keyCodec, PlayerField<K> playerField) {
        return new LinkType<>(name, keyCodec, playerField);
    }

    public String name() {
        return name;
    }

    public Codec<K> keyCodec() {
        return keyCodec;
    }

    public PlayerField<K> playerField() {
        return playerField;
    }
}
