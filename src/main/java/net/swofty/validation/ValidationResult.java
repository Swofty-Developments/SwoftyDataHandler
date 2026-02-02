package net.swofty.validation;

public record ValidationResult(boolean isValid, String error) {
    public static ValidationResult valid() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult invalid(String error) {
        return new ValidationResult(false, error);
    }
}
