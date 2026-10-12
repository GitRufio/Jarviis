package pl.jarvis.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionManagerTest {
    private final PermissionManager permissions = new PermissionManager();

    @Test
    void allowsSafeOperationWithoutConfirmation() {
        assertTrue(permissions.evaluate(RiskLevel.SAFE, false).allowed());
    }

    @Test
    void requiresConfirmationForDangerousOperation() {
        PermissionManager.PermissionDecision decision = permissions.evaluate(RiskLevel.DANGEROUS, false);
        assertFalse(decision.allowed());
        assertTrue(decision.needsConfirmation());
    }

    @Test
    void neverAllowsBlockedOperation() {
        assertFalse(permissions.evaluate(RiskLevel.BLOCKED, true).allowed());
    }
}
