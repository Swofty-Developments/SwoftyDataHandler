package net.swofty.validation;

@FunctionalInterface
public interface Validator<T> {
    ValidationResult validate(T value);

    default Validator<T> and(Validator<T> other) {
        return value -> {
            ValidationResult result = this.validate(value);
            return result.isValid() ? other.validate(value) : result;
        };
    }
}
