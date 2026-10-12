package pl.jarvis.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.jarvis.core.Capability;
import pl.jarvis.core.Task;
import pl.jarvis.core.TaskContext;
import pl.jarvis.hardware.HardwareProfile;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;
import pl.jarvis.models.ModelProvider;
import pl.jarvis.models.ModelRegistry;
import pl.jarvis.security.PermissionManager;
import pl.jarvis.tools.ToolManager;
import pl.jarvis.tools.ToolRegistry;
import pl.jarvis.tools.WorkspaceFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentTest {
    @TempDir
    Path workspace;

    @Test
    void boundsMinecraftPluginGenerationAndWritesTheCompleteProject() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        ModelDescriptor model = new ModelDescriptor(
                "test-coder", "test", EnumSet.of(Capability.CODING),
                8192, 0, 0, 0, 0, "none", false, 1.0, 1.0, true
        );
        ModelRegistry models = new ModelRegistry();
        models.register(model);

        ToolRegistry tools = new ToolRegistry();
        tools.register(new WorkspaceFileTool(workspace));
        Task task = new Task("Napisz prosty plugin do Minecrafta w Javie", EnumSet.of(Capability.CODING));
        task.selectModel(model.id());

        String result = new CodingAgent().execute(new TaskContext(
                task,
                new HardwareProfile("test", 1, 1, 1, "none", 0, 0, "test", "test", java.util.List.of()),
                new ModelManager(models, provider),
                new ToolManager(tools, new PermissionManager())
        ));

        assertEquals(1024, provider.maxTokens);
        assertTrue(result.contains("Zapisano pliki projektu"));
        assertTrue(Files.exists(workspace.resolve("minecraft-plugin/pom.xml")));
        assertTrue(Files.exists(workspace.resolve("minecraft-plugin/src/main/java/com/example/plugin/ExamplePlugin.java")));
        assertTrue(Files.exists(workspace.resolve("minecraft-plugin/src/main/resources/plugin.yml")));
    }

    @Test
    void returnsOrdinaryCodingAnswersWithoutWritingModelFileDirectives() throws Exception {
        String modelAnswer = """
                [[JARVIS_FILE path="src/main/java/example/Hello.java"]]
                package example;
                public final class Hello {}
                [[/JARVIS_FILE]]
                """;
        RecordingProvider provider = new RecordingProvider(modelAnswer);
        ModelDescriptor model = new ModelDescriptor(
                "test-coder", "test", EnumSet.of(Capability.CODING),
                8192, 0, 0, 0, 0, "none", false, 1.0, 1.0, true
        );
        ModelRegistry models = new ModelRegistry();
        models.register(model);
        ToolRegistry tools = new ToolRegistry();
        tools.register(new WorkspaceFileTool(workspace));
        Task task = new Task("Wypisz w czacie klasę Java Hello World", EnumSet.of(Capability.CODING));
        task.selectModel(model.id());

        String result = new CodingAgent().execute(new TaskContext(
                task,
                new HardwareProfile("test", 1, 1, 1, "none", 0, 0, "test", "test", java.util.List.of()),
                new ModelManager(models, provider),
                new ToolManager(tools, new PermissionManager())
        ));

        assertEquals(modelAnswer, result);
        assertTrue(task.toolCalls().isEmpty());
        assertFalse(Files.exists(workspace.resolve("src/main/java/example/Hello.java")));
    }

    private static final class RecordingProvider implements ModelProvider {
        private int maxTokens = -1;
        private final String answer;

        private RecordingProvider() {
            this(pluginProject());
        }

        private RecordingProvider(String answer) {
            this.answer = answer;
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<String> generate(String modelId, String systemPrompt, String userInput) {
            return Optional.of(answer);
        }

        @Override
        public Optional<String> generate(String modelId, String systemPrompt, String userInput, int maxTokens) {
            this.maxTokens = maxTokens;
            return Optional.of(answer);
        }

        private static String pluginProject() {
            return """
                    [[JARVIS_FILE path="minecraft-plugin/pom.xml"]]
                    <project><modelVersion>4.0.0</modelVersion></project>
                    [[/JARVIS_FILE]]
                    [[JARVIS_FILE path="minecraft-plugin/src/main/java/com/example/plugin/ExamplePlugin.java"]]
                    package com.example.plugin;
                    public final class ExamplePlugin {}
                    [[/JARVIS_FILE]]
                    [[JARVIS_FILE path="minecraft-plugin/src/main/resources/plugin.yml"]]
                    name: ExamplePlugin
                    main: com.example.plugin.ExamplePlugin
                    [[/JARVIS_FILE]]
                    """;
        }
    }
}
