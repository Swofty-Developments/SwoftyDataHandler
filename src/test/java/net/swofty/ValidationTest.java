package net.swofty;

import net.swofty.api.DataAPIImpl;
import net.swofty.codec.Codecs;
import net.swofty.data.format.JsonFormat;
import net.swofty.storage.FileDataStorage;
import net.swofty.validation.ValidationException;
import net.swofty.validation.ValidationResult;
import net.swofty.validation.Validator;
import net.swofty.validation.Validators;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {

    @TempDir
    Path tempDir;
    private DataAPIImpl api;

    @BeforeEach
    void setUp() {
        api = new DataAPIImpl(new FileDataStorage(tempDir, new JsonFormat(), ".json"), new JsonFormat());
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    // ==================== Validator Unit Tests ====================

    @Test
    void nonNegativeAcceptsZero() {
        assertEquals(true, Validators.nonNegative().validate(0).isValid());
    }

    @Test
    void nonNegativeAcceptsPositive() {
        assertEquals(true, Validators.nonNegative().validate(100).isValid());
    }

    @Test
    void nonNegativeRejectsNegative() {
        ValidationResult result = Validators.nonNegative().validate(-1);
        assertFalse(result.isValid());
        assertEquals("Value cannot be negative", result.error());
    }

    @Test
    void rangeAcceptsMinBound() {
        assertTrue(Validators.range(1, 100).validate(1).isValid());
    }

    @Test
    void rangeAcceptsMaxBound() {
        assertTrue(Validators.range(1, 100).validate(100).isValid());
    }

    @Test
    void rangeAcceptsMidValue() {
        assertTrue(Validators.range(1, 100).validate(50).isValid());
    }

    @Test
    void rangeRejectsBelowMin() {
        assertFalse(Validators.range(1, 100).validate(0).isValid());
    }

    @Test
    void rangeRejectsAboveMax() {
        assertFalse(Validators.range(1, 100).validate(101).isValid());
    }

    @Test
    void maxLengthAcceptsExactLength() {
        assertTrue(Validators.maxLength(5).validate("abcde").isValid());
    }

    @Test
    void maxLengthAcceptsShorter() {
        assertTrue(Validators.maxLength(5).validate("abc").isValid());
    }

    @Test
    void maxLengthRejectsLonger() {
        assertFalse(Validators.maxLength(5).validate("abcdef").isValid());
    }

    @Test
    void maxLengthAcceptsEmpty() {
        assertTrue(Validators.maxLength(5).validate("").isValid());
    }

    // ==================== Composed Validators ====================

    @Test
    void andCompositionBothPass() {
        Validator<Integer> both = Validators.nonNegative().and(Validators.range(0, 1000));
        assertTrue(both.validate(500).isValid());
    }

    @Test
    void andCompositionFirstFails() {
        Validator<Integer> both = Validators.nonNegative().and(Validators.range(0, 1000));
        ValidationResult result = both.validate(-1);
        assertFalse(result.isValid());
        assertEquals("Value cannot be negative", result.error());
    }

    @Test
    void andCompositionSecondFails() {
        Validator<Integer> both = Validators.nonNegative().and(Validators.range(0, 1000));
        ValidationResult result = both.validate(1001);
        assertFalse(result.isValid());
        assertNotNull(result.error());
    }

    @Test
    void customValidatorWithAnd() {
        Validator<String> custom = Validators.maxLength(16).and(value ->
                value.matches("^[a-zA-Z0-9_]*$")
                        ? ValidationResult.valid()
                        : ValidationResult.invalid("Invalid characters"));

        assertTrue(custom.validate("ValidName_123").isValid());
        assertFalse(custom.validate("bad name!").isValid());
        assertFalse(custom.validate("way_too_long_nickname_here").isValid());
    }

    // ==================== Integration with DataAPI ====================

    @Test
    void setValidValueSucceeds() {
        PlayerField<Integer> field = PlayerField.<Integer>builder("test", "validated")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, field, 100);
        assertEquals(100, api.get(player, field));
    }

    @Test
    void setInvalidValueThrowsValidationException() {
        PlayerField<Integer> field = PlayerField.<Integer>builder("test", "validated2")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        ValidationException ex = assertThrows(ValidationException.class, () ->
                api.set(player, field, -1));

        assertEquals("Value cannot be negative", ex.getMessage());
        assertEquals(field, ex.getField());
        assertEquals(-1, ex.getValue());
    }

    @Test
    void invalidSetDoesNotChangeValue() {
        PlayerField<Integer> field = PlayerField.<Integer>builder("test", "validated3")
                .codec(Codecs.INT)
                .defaultValue(0)
                .validator(Validators.nonNegative())
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, field, 100);

        assertThrows(ValidationException.class, () -> api.set(player, field, -1));
        assertEquals(100, api.get(player, field));
    }

    @Test
    void updateWithInvalidResultThrowsValidationException() {
        PlayerField<Integer> field = PlayerField.<Integer>builder("test", "validated4")
                .codec(Codecs.INT)
                .defaultValue(10)
                .validator(Validators.range(0, 100))
                .build();

        UUID player = UUID.randomUUID();
        api.set(player, field, 90);

        assertThrows(ValidationException.class, () ->
                api.update(player, field, v -> v + 20)); // 110 > 100
    }

    @Test
    void fieldWithoutValidatorAcceptsAnyValue() {
        PlayerField<Integer> field = PlayerField.create("test", "noval", Codecs.INT, 0);

        UUID player = UUID.randomUUID();
        api.set(player, field, -999);
        assertEquals(-999, api.get(player, field));
    }

    @Test
    void linkedFieldValidation() {
        PlayerField<UUID> islandIdField = PlayerField.create("test", "island_id_v", Codecs.nullable(Codecs.UUID), null);
        LinkType<UUID> island = LinkType.create("island_v", Codecs.UUID, islandIdField);

        LinkedField<UUID, Integer> level = LinkedField.<UUID, Integer>builder("test", "level_v", island)
                .codec(Codecs.INT)
                .defaultValue(1)
                .validator(Validators.range(1, 100))
                .build();

        UUID player = UUID.randomUUID();
        UUID islandId = UUID.randomUUID();
        api.link(player, island, islandId);

        api.setDirect(islandId, level, 50);
        assertEquals(50, api.getDirect(islandId, level));

        assertThrows(ValidationException.class, () ->
                api.setDirect(islandId, level, 0));
        assertThrows(ValidationException.class, () ->
                api.setDirect(islandId, level, 101));
    }
}
