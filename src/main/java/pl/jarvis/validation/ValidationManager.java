package pl.jarvis.validation;

import pl.jarvis.core.Task;

public final class ValidationManager {
    public ValidationResult validate(Task task) {
        if (task.result() == null || task.result().isBlank()) {
            return ValidationResult.invalid("Agent returned an empty result");
        }
        if (task.selectedAgent() == null || task.selectedAgent().isBlank()) {
            return ValidationResult.invalid("No agent was selected");
        }
        return ValidationResult.success();
    }

    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult success() { return new ValidationResult(true, ""); }
        public static ValidationResult invalid(String reason) { return new ValidationResult(false, reason); }
    }
}
