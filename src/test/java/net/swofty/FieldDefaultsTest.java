package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codec;
import net.swofty.codec.Codecs;
import net.swofty.data.DataReader;
import net.swofty.data.DataWriter;
import net.swofty.storage.InMemoryDataStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading a field that has never been written is the hot path — it happens on every first access to
 * every field of every player — so the default must be the constant it was declared as, not
 * something rebuilt through the codec each time.
 */
class FieldDefaultsTest {

    private static final class CountingCodec<T> implements Codec<T> {
        private final Codec<T> delegate;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();

        CountingCodec(Codec<T> delegate) {
            this.delegate = delegate;
        }

        @Override public T read(DataReader reader) {
            reads.incrementAndGet();
            return delegate.read(reader);
        }

        @Override public void write(DataWriter writer, T value) {
            writes.incrementAndGet();
            delegate.write(writer, value);
        }
    }

    @Test
    void aConstantDefaultIsTheSameInstanceEveryTimeAndNeverTouchesTheCodec() {
        CountingCodec<List<String>> codec = new CountingCodec<>(Codecs.list(Codecs.STRING));
        List<String> prototype = List.of("hello");
        PlayerField<List<String>> field = PlayerField.<List<String>>builder("defaults", "greetings")
                .codec(codec)
                .defaultValue(prototype)
                .build();

        assertSame(prototype, field.defaultValue());
        assertSame(field.defaultValue(), field.defaultValue());
        assertEquals(0, codec.reads.get());
        assertEquals(0, codec.writes.get());

        InMemoryDataStorage storage = new InMemoryDataStorage();
        try (DataAPI api = new DataAPIImpl(storage)) {
            UUID player = UUID.randomUUID();
            for (int i = 0; i < 10; i++) {
                assertSame(prototype, api.get(player, field));
            }
            assertEquals(0, codec.reads.get(), "a default must not be decoded on a cache miss");
            assertEquals(0, codec.writes.get());
        }
    }

    @Test
    void aDefaultThatCannotBeEncodedStillBuildsAndReads() {
        Codec<String> writeOnlyFails = new Codec<>() {
            @Override public String read(DataReader reader) { return reader.readString(); }
            @Override public void write(DataWriter writer, String value) {
                throw new UnsupportedOperationException("this codec cannot encode " + value);
            }
        };

        PlayerField<String> field = assertDoesNotThrow(() -> PlayerField.<String>builder("defaults", "sentinel")
                .codec(writeOnlyFails)
                .defaultValue("<none>")
                .build());
        assertEquals("<none>", field.defaultValue());
    }

    @Test
    void aDefaultFactoryHandsOutAFreshValueEachTime() {
        PlayerField<List<String>> field = PlayerField.<List<String>>builder("defaults", "mutable")
                .codec(Codecs.list(Codecs.STRING))
                .defaultFactory(ArrayList::new)
                .build();

        List<String> first = field.defaultValue();
        List<String> second = field.defaultValue();
        assertNotSame(first, second);

        first.add("mutated");
        assertTrue(field.defaultValue().isEmpty(), "a factory default must not be shared state");
    }

    @Test
    void linkedAndExpiringFieldsHonourBothDefaultStyles() {
        PlayerField<UUID> coopKey = PlayerField.create("defaults", "coop_key", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> coop = LinkType.create("defaults_coop", Codecs.UUID, coopKey);

        List<String> prototype = List.of("shared");
        LinkedField<UUID, List<String>> constant = LinkedField.<UUID, List<String>>builder("defaults", "roster", coop)
                .codec(Codecs.list(Codecs.STRING))
                .defaultValue(prototype)
                .build();
        assertSame(prototype, constant.defaultValue());

        LinkedField<UUID, List<String>> fresh = LinkedField.<UUID, List<String>>builder("defaults", "invites", coop)
                .codec(Codecs.list(Codecs.STRING))
                .defaultFactory(ArrayList::new)
                .build();
        assertNotSame(fresh.defaultValue(), fresh.defaultValue());

        ExpiringField<List<String>> expiring = ExpiringField.<List<String>>expiringBuilder("defaults", "boosts")
                .codec(Codecs.list(Codecs.STRING))
                .defaultFactory(ArrayList::new)
                .defaultTtl(java.time.Duration.ofMinutes(1))
                .build();
        assertNotSame(expiring.defaultValue(), expiring.defaultValue());
    }
}
