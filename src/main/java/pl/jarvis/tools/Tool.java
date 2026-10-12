package pl.jarvis.tools;

import pl.jarvis.security.RiskLevel;

import java.util.Map;

public interface Tool {
    String id();
    String description();
    String schema();
    RiskLevel riskLevel(Map<String, String> arguments);
    ToolResult execute(Map<String, String> arguments) throws ToolException;
}
