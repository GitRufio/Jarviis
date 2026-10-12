package pl.jarvis.agents;

import pl.jarvis.core.Capability;

import java.util.EnumSet;
import java.util.Locale;

public final class AgentRouter {
    private final AgentRegistry registry;

    public AgentRouter(AgentRegistry registry) { this.registry = registry; }

    public EnumSet<Capability> inferCapabilities(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "cpu", "ram", "dysk", "system", "proces", "sprzęt", "komputer")) {
            return EnumSet.of(Capability.SYSTEM);
        }
        if (containsAny(normalized, "kod", "program", "plugin", "debug", "błąd", "test", "git", "refaktor", "java", "python")) {
            return EnumSet.of(Capability.CODING);
        }
        if (containsAny(normalized, "wyszukaj", "zbadaj", "research", "dokumentacja", "źródła", "porównaj")) {
            return EnumSet.of(Capability.RESEARCH);
        }
        return EnumSet.of(Capability.GENERAL);
    }

    public Agent route(EnumSet<Capability> requirements) {
        return registry.bestMatch(requirements)
                .orElseThrow(() -> new IllegalStateException("No registered agent supports " + requirements));
    }

    private boolean containsAny(String input, String... keywords) {
        for (String keyword : keywords) {
            int index = input.indexOf(keyword);
            while (index >= 0) {
                int after = index + keyword.length();
                boolean startsTerm = index == 0 || !Character.isLetterOrDigit(input.charAt(index - 1));
                boolean endsTerm = after == input.length() || !Character.isLetterOrDigit(input.charAt(after));
                if (startsTerm && endsTerm) {
                    return true;
                }
                index = input.indexOf(keyword, index + 1);
            }
        }
        return false;
    }
}
