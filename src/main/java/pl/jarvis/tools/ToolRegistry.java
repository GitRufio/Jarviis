package pl.jarvis.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) { tools.put(tool.id(), tool); }
    public Optional<Tool> find(String id) { return Optional.ofNullable(tools.get(id)); }
    public Collection<Tool> all() { return java.util.List.copyOf(tools.values()); }
}
