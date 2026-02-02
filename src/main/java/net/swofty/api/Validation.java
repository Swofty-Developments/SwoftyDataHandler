package net.swofty.api;

import net.swofty.DataField;
import net.swofty.LinkedField;
import net.swofty.PlayerField;
import net.swofty.validation.ValidationException;
import net.swofty.validation.ValidationResult;
import net.swofty.validation.Validator;

public final class Validation {
    private Validation() {}

    @SuppressWarnings("unchecked")
    public static <T> void validate(DataField<T> field, T value) {
        Validator<T> validator = getValidator(field);
        if (validator != null && value != null) {
            ValidationResult result = validator.validate(value);
            if (!result.isValid()) {
                throw new ValidationException(result.error(), field, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Validator<T> getValidator(DataField<T> field) {
        if (field instanceof PlayerField<?> pf) return ((PlayerField<T>) pf).validator();
        if (field instanceof LinkedField<?, ?> lf) return ((LinkedField<?, T>) lf).validator();
        return null;
    }
}
