package pl.jarvis.agents;

import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Router agentów oparty o model AI zamiast słów-kluczy. Model dostaje listę
 *  agentów z opisami i klasyfikuje polecenie użytkownika, odpowiadając
 *  krótkim JSON-em. Niepowodzenie (brak modelu, timeout, zły format)
 *  sygnalizuje pustym Optional - wtedy wołający spada na router ze słów-kluczy. */
public final class LlmAgentRouter {
    private static final String SYSTEM =
            "You route user requests to agents. Reply with ONLY one JSON object, no explanations.";
    private static final Pattern AGENT_JSON =
            Pattern.compile("\"agent\"\\s*:\\s*\"([A-Za-z_]+)\"");

    private final AgentRegistry registry;
    private final ModelManager models;
    private final ModelDescriptor routerModel;

    /** @param routerModel model do klasyfikacji; null wyłącza router (zawsze fallback). */
    public LlmAgentRouter(AgentRegistry registry, ModelManager models, ModelDescriptor routerModel) {
        this.registry = registry;
        this.models = models;
        this.routerModel = routerModel;
    }

    public Optional<Agent> route(String userInput) {
        if (routerModel == null || userInput == null || userInput.isBlank()) return Optional.empty();
        try {
            // think=false: klasyfikacja ma być natychmiastowa, bez toku rozumowania.
            return models.generate(routerModel, SYSTEM, prompt(userInput), 64, false)
                    .filter(answer -> !answer.isBlank())
                    .flatMap(this::parseAgent);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String prompt(String userInput) {
        String agents = registry.all().stream()
                .map(agent -> "- %s: %s (capabilities: %s)".formatted(agent.id(), agent.name(),
                        agent.capabilities().stream().map(Enum::name).sorted().collect(Collectors.joining(", "))))
                .collect(Collectors.joining("\n"));
        return """
                Available agents:
                %s
                Allowed agent ids (use EXACTLY one of these): general, system, coding, research.

                Examples:
                User: "Sprawdź zużycie RAM" -> {"agent":"system"}
                User: "Check CPU temperature" -> {"agent":"system"}
                User: "Napisz funkcję w Pythonie" -> {"agent":"coding"}
                User: "Znajdź dokumentację biblioteki" -> {"agent":"research"}
                User: "Jaka jest stolica Polski?" -> {"agent":"general"}

                User: "%s" ->""".formatted(agents, userInput.replace("\"", "'")).strip();
    }

    private Optional<Agent> parseAgent(String answer) {
        Matcher matcher = AGENT_JSON.matcher(answer);
        if (matcher.find()) {
            String id = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            Optional<Agent> exact = registry.all().stream().filter(agent -> agent.id().equals(id)).findFirst();
            if (exact.isPresent()) return exact;
            // Model czasem wymyśla wariant ("system_monitor") - mapujemy na znane id.
            return registry.all().stream().filter(agent -> id.contains(agent.id())).findFirst();
        }
        return Optional.empty();
    }
}
