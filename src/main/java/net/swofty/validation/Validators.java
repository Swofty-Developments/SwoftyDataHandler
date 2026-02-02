package net.swofty.validation;

public final class Validators {
    private Validators() {}

    public static Validator<Integer> nonNegative() {
        return value -> value >= 0
                ? ValidationResult.valid()
                : ValidationResult.invalid("Value cannot be negative");
    }

    public static Validator<Integer> range(int min, int max) {
        return value -> (value >= min && value <= max)
                ? ValidationResult.valid()
                : ValidationResult.invalid("Value must be between " + min + " and " + max);
    }

    public static Validator<String> maxLength(int max) {
        return value -> value.length() <= max
                ? ValidationResult.valid()
                : ValidationResult.invalid("String exceeds max length of " + max);
    }
}
