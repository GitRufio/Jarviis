package pl.jarvis.tools;

import org.junit.jupiter.api.Test;
import pl.jarvis.core.Capability;
import pl.jarvis.core.Task;
import pl.jarvis.security.PermissionManager;
import pl.jarvis.security.RiskLevel;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolManagerTest {
    @Test
    void doesNotInvokeDangerousToolWithoutConfirmation() {
        Tool dangerousTool = new Tool() {
            public String id() { return "danger"; }
            public String description() { return "test"; }
            public String schema() { return "{}"; }
            public RiskLevel riskLevel(Map<String, String> arguments) { return RiskLevel.DANGEROUS; }
            public ToolResult execute(Map<String, String> arguments) { throw new AssertionError("Must not execute"); }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(dangerousTool);
        ToolManager manager = new ToolManager(registry, new PermissionManager());
        Task task = new Task("Test permission", EnumSet.of(Capability.GENERAL));

        assertThrows(ToolPermissionException.class, () -> manager.execute(task, "danger", Map.of(), false));
        assertFalse(task.toolCalls().contains("danger:execute"));
    }
}
