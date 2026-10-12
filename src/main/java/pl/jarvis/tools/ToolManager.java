package pl.jarvis.tools;

import pl.jarvis.core.Task;
import pl.jarvis.security.PermissionManager;

import java.util.Map;

public final class ToolManager {
    private final ToolRegistry registry;
    private final PermissionManager permissions;

    public ToolManager(ToolRegistry registry, PermissionManager permissions) {
        this.registry = registry;
        this.permissions = permissions;
    }

    public ToolResult execute(Task task, String toolId, Map<String, String> arguments, boolean userConfirmed) throws ToolException {
        Tool tool = registry.find(toolId).orElseThrow(() -> new ToolException("Unknown tool: " + toolId));
        PermissionManager.PermissionDecision decision = permissions.evaluate(tool.riskLevel(arguments), userConfirmed);
        if (!decision.allowed()) {
            throw new ToolPermissionException(decision.reason(), decision.needsConfirmation());
        }
        task.addToolCall(toolId + ":" + arguments.getOrDefault("action", "execute"));
        return tool.execute(arguments);
    }
}
