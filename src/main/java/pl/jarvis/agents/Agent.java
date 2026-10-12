package pl.jarvis.agents;

import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskContext;

import java.util.EnumSet;
import java.util.Set;

public interface Agent {
    String id();
    String name();
    EnumSet<Capability> capabilities();
    Set<String> tools();
    String execute(TaskContext context) throws Exception;
}
