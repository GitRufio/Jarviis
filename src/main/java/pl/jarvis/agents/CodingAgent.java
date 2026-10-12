
package pl.jarvis.agents;

import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskContext;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.tools.ToolException;
import pl.jarvis.tools.ToolResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodingAgent implements Agent {
    // Kompletny minimalny plugin (pom.xml, klasa i plugin.yml) mieści się
    // z zapasem w tym limicie. Bez num_predict Ollama może generować bardzo
    // długo, przez co synchroniczne /v1/tasks pozostaje bez odpowiedzi.
    private static final int MAX_GENERATION_TOKENS = 1024;

    private static final Pattern FILE_BLOCK = Pattern.compile(
            "(?s)\\[\\[JARVIS_FILE path=\"([^\"]+)\"\\]\\]"
                    + "\\R(.*?)\\R\\[\\[/JARVIS_FILE\\]\\]"
    );

    private static final String SYSTEM_PROMPT = """
            You are JARVIS Coding Agent.
            Complete programming tasks instead of merely discussing them.

            Respond in Polish when the user speaks Polish.

            For requests to create a Minecraft plugin:
            - Use Java and the Paper API.
            - Choose a simple gameplay feature yourself if needed.
            - Do not ask unnecessary follow-up questions.
            - Create a complete minimal Maven project.
            - Use Java 21 and a compatible Paper API dependency.
            - Include pom.xml, a Java main class and plugin.yml.
            - Ensure the package, main class and plugin.yml agree.
            - Include all required imports and event registration.
            - Do not claim that the code has been compiled or tested.

            Output each file in this exact format, without Markdown fences:

            [[JARVIS_FILE path="minecraft-plugin/pom.xml"]]
            raw file content
            [[/JARVIS_FILE]]

            [[JARVIS_FILE path="minecraft-plugin/src/main/java/com/example/plugin/ExamplePlugin.java"]]
            raw Java source code
            [[/JARVIS_FILE]]

            [[JARVIS_FILE path="minecraft-plugin/src/main/resources/plugin.yml"]]
            raw YAML content
            [[/JARVIS_FILE]]

            Replace the example Java path with the actual path for your class.
            Keep all generated paths relative to the workspace.
            Never use absolute paths or paths containing '..'.

            For tasks that do not request file creation, answer normally.
            """;

    @Override
    public String id() {
        return "coding";
    }

    @Override
    public String name() {
        return "Coding Agent";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(Capability.CODING);
    }

    @Override
    public Set<String> tools() {
        return Set.of("workspace_file", "file", "terminal");
    }

    @Override
    public String execute(TaskContext context) throws Exception {
        Optional<ModelDescriptor> model =
                context.task().selectedModel() == null
                        ? Optional.empty()
                        : context.models().descriptor(
                        context.task().selectedModel()
                );

        if (model.isEmpty()) {
            return "Nie wybrano dostępnego lokalnego modelu programistycznego.";
        }

        Optional<String> generated = context.models().generate(
                model.get(),
                SYSTEM_PROMPT,
                context.task().userInput(),
                MAX_GENERATION_TOKENS
        );

        if (generated.isEmpty() || generated.get().isBlank()) {
            return "Model programistyczny nie zwrócił odpowiedzi.";
        }

        String output = generated.get();
        boolean pluginRequest = isMinecraftPluginRequest(
                context.task().userInput()
        );

        // File directives are the plugin-generation protocol, not a general
        // response format. A model can still emit a directive while answering
        // an ordinary coding question; treating that as a write request makes
        // a safe chat response fail because it is outside the plugin workspace.
        if (!pluginRequest) {
            return output;
        }

        List<GeneratedFile> files = parseFiles(output);

        if (files.isEmpty()) {
            return "Model nie zwrócił plików w wymaganym formacie. "
                    + "Nie zapisano żadnych plików.\n\n"
                    + "Odpowiedź modelu:\n" + output;
        }

        if (!containsRequiredPluginFiles(files)) {
            return "Model nie przygotował kompletnego zestawu plików "
                    + "pluginu. Nie zapisano żadnych plików.\n\n"
                    + "Liczba rozpoznanych plików: " + files.size()
                    + "\n\nSurowa odpowiedź modelu:\n" + output;
        }

        List<String> written = new ArrayList<>();

        for (GeneratedFile file : files) {
            if (!file.path().startsWith("minecraft-plugin/")) {
                throw new ToolException(
                        "Generated path is outside the plugin project"
                );
            }

            ToolResult result = context.tools().execute(
                    context.task(),
                    "workspace_file",
                    Map.of(
                            "action", "write",
                            "path", file.path(),
                            "content", file.content()
                    ),
                    false
            );

            written.add(result.content());
        }

        return "Zapisano pliki projektu w obszarze roboczym Jarvisa:\n"
                + String.join("\n", written)
                + "\n\nProjekt nie został jeszcze skompilowany.";
    }

    private static List<GeneratedFile> parseFiles(String output) {
        List<GeneratedFile> files = new ArrayList<>();
        Matcher matcher = FILE_BLOCK.matcher(output);

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String content = matcher.group(2) + "\n";

            files.add(new GeneratedFile(path, content));
        }

        return files;
    }

    private static boolean containsRequiredPluginFiles(
            List<GeneratedFile> files
    ) {
        boolean hasPom = files.stream().anyMatch(
                file -> file.path().equals("minecraft-plugin/pom.xml")
        );

        boolean hasPluginYml = files.stream().anyMatch(
                file -> file.path().equals(
                        "minecraft-plugin/src/main/resources/plugin.yml"
                )
        );

        boolean hasJavaClass = files.stream().anyMatch(
                file -> file.path().startsWith(
                        "minecraft-plugin/src/main/java/"
                ) && file.path().endsWith(".java")
        );

        return hasPom && hasPluginYml && hasJavaClass;
    }

    private static boolean isMinecraftPluginRequest(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);

        return normalized.contains("minecraft")
                && normalized.contains("plugin");
    }

    private record GeneratedFile(String path, String content) {
    }
}
