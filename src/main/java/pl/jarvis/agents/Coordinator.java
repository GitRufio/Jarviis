
package pl.jarvis.agents;

import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Coordinator {

    private static final int MAX_TOKENS = 512;

    private static final String SYSTEM = """
        You are JARVIS, an AI task coordinator.

        Your job is to decide who should perform the task.
        You are NOT the implementation agent.

        Return exactly one JSON object.

        For a direct answer:
        {"action":"answer","answer":"Your concise answer"}

        For delegation:
        {"action":"delegate","agent":"exact_agent_id"}

        Rules:
        1. Answer simple factual and conversational questions yourself.
        2. ALWAYS delegate programming and code-generation tasks
           to the coding agent.
        3. This includes Minecraft plugins, Java classes, scripts,
           debugging, and requests to create or modify source files.
        4. NEVER write code yourself. Delegate coding tasks instead.
        5. The latest user message is the active request.
           Use previous conversation messages only as context.
        6. If the user says "choose anything", "use Java",
           or "come up with something", treat that as permission
           to choose reasonable defaults and complete the task.
           Do not keep asking unnecessary questions.
        7. Delegate system and hardware tasks to system.
        8. Delegate research and documentation searches to research.
        9. Never invent agent IDs.
        10. Answer in the user's language.

        Return only the required JSON object.
        """;

    private final AgentRegistry registry;
    private final ModelManager models;
    private final ModelDescriptor coordinatorModel;

    public Coordinator(
            AgentRegistry registry,
            ModelManager models,
            ModelDescriptor coordinatorModel
    ) {
        this.registry = registry;
        this.models = models;
        this.coordinatorModel = coordinatorModel;
    }

    public Optional<CoordinatorDecision> decide(String userInput) {
        if (coordinatorModel == null
                || userInput == null
                || userInput.isBlank()) {
            return Optional.empty();
        }

        try {
            Optional<String> response = models.generate(
                    coordinatorModel,
                    SYSTEM,
                    prompt(userInput),
                    MAX_TOKENS,
                    false
            );

            if (response.isEmpty()) {
                return Optional.empty();
            }

            String json = response.get();

            Optional<String> action =
                    stringField(json, "action");

            if (action.isEmpty()) {
                return Optional.empty();
            }

            if ("answer".equalsIgnoreCase(action.get())) {
                return stringField(json, "answer")
                        .filter(answer -> !answer.isBlank())
                        .map(answer -> new CoordinatorDecision(
                                CoordinatorDecision.Action.ANSWER,
                                answer,
                                null
                        ));
            }

            if ("delegate".equalsIgnoreCase(action.get())) {
                Optional<String> agentId =
                        stringField(json, "agent");

                if (agentId.isEmpty()
                        || registry.find(agentId.get()).isEmpty()) {
                    return Optional.empty();
                }

                return Optional.of(new CoordinatorDecision(
                        CoordinatorDecision.Action.DELEGATE,
                        null,
                        agentId.get()
                ));
            }

            return Optional.empty();

        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<Agent> resolveAgent(String agentId) {
        return registry.find(agentId);
    }

    public Optional<String> modelId() {
        return Optional.ofNullable(coordinatorModel)
                .map(ModelDescriptor::id);
    }

    private String prompt(String userInput) {
        String availableAgents = registry.all().stream()
                .map(agent -> "- %s: %s (capabilities: %s)".formatted(
                        agent.id(),
                        agent.name(),
                        agent.capabilities().stream()
                                .map(Enum::name)
                                .sorted()
                                .collect(Collectors.joining(", "))
                ))
                .collect(Collectors.joining("\n"));

        return """
                Available agents:
                %s

                User request:
                %s

                Decide whether to answer or delegate.
                Return only the required JSON object.
                """.formatted(availableAgents, userInput);
    }

    private static Optional<String> stringField(
            String json,
            String field
    ) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field)
                        + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        );

        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return Optional.empty();
        }

        return decodeJsonString(matcher.group(1));
    }

    private static Optional<String> decodeJsonString(String value) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);

            if (current != '\\') {
                result.append(current);
                continue;
            }

            if (++i >= value.length()) {
                return Optional.empty();
            }

            char escaped = value.charAt(i);

            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');

                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        return Optional.empty();
                    }

                    try {
                        int code = Integer.parseInt(
                                value.substring(i + 1, i + 5),
                                16
                        );
                        result.append((char) code);
                        i += 4;
                    } catch (NumberFormatException exception) {
                        return Optional.empty();
                    }
                }

                default -> {
                    return Optional.empty();
                }
            }
        }

        return Optional.of(result.toString());
    }
}
