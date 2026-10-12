package pl.jarvis.agents;

import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskContext;
import pl.jarvis.tools.ToolResult;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class SystemAgent implements Agent {
    @Override public String id() { return "system"; }
    @Override public String name() { return "System Agent"; }
    @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.SYSTEM); }
    @Override public Set<String> tools() { return Set.of("system"); }

    @Override
    public String execute(TaskContext context) throws Exception {
        ToolResult result = context.tools().execute(context.task(), "system", Map.of("action", "stats"), false);
        return result.content();
    }
}
