package pl.jarvis.agents;

import pl.jarvis.core.Capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class AgentRegistry {
    private final List<Agent> agents = new ArrayList<>();

    public void register(Agent agent) { agents.add(agent); }

    public Optional<Agent> bestMatch(EnumSet<Capability> requirements) {
        return agents.stream()
                .filter(agent -> agent.capabilities().containsAll(requirements))
                .max(Comparator.comparingInt(agent -> agent.capabilities().size()));
    }

    public Collection<Agent> all() { return List.copyOf(agents); }

    public Optional<Agent> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        return agents.stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst();
    }
}
