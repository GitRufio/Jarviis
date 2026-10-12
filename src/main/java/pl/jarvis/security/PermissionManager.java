package pl.jarvis.security;

public final class PermissionManager {
    public PermissionDecision evaluate(RiskLevel risk, boolean userConfirmed) {
        return switch (risk) {
            case SAFE -> PermissionDecision.allowed("Safe operation");
            case CAUTION -> userConfirmed
                    ? PermissionDecision.allowed("User confirmed caution operation")
                    : PermissionDecision.awaitingConfirmation("Caution operation requires confirmation");
            case DANGEROUS -> userConfirmed
                    ? PermissionDecision.allowed("User confirmed dangerous operation")
                    : PermissionDecision.awaitingConfirmation("Dangerous operation requires explicit confirmation");
            case BLOCKED -> PermissionDecision.denied("Operation is blocked by policy");
        };
    }

    public record PermissionDecision(boolean allowed, boolean needsConfirmation, String reason) {
        public static PermissionDecision allowed(String reason) { return new PermissionDecision(true, false, reason); }
        public static PermissionDecision awaitingConfirmation(String reason) { return new PermissionDecision(false, true, reason); }
        public static PermissionDecision denied(String reason) { return new PermissionDecision(false, false, reason); }
    }
}
