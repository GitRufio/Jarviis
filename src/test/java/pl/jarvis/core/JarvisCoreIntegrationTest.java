package pl.jarvis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.jarvis.agents.Agent;
import pl.jarvis.agents.AgentRegistry;
import pl.jarvis.agents.AgentRouter;
import pl.jarvis.agents.SystemAgent;
import pl.jarvis.hardware.HardwareManager;
import pl.jarvis.logging.AuditLogger;
import pl.jarvis.memory.SqliteMemoryStore;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;
import pl.jarvis.models.ModelProvider;
import pl.jarvis.models.ModelRegistry;
import pl.jarvis.models.ModelRouter;
import pl.jarvis.recovery.RecoveryManager;
import pl.jarvis.security.PermissionManager;
import pl.jarvis.tools.SystemTool;
import pl.jarvis.tools.ToolRegistry;
import pl.jarvis.tools.ToolManager;
import pl.jarvis.validation.ValidationManager;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarvisCoreIntegrationTest {
    @TempDir Path tempDirectory;

    @Test
    void executesSafeSystemFlowAndPersistsResult() {
        AgentRegistry agents = new AgentRegistry();
        agents.register(new SystemAgent());
        AgentRouter router = new AgentRouter(agents);
        ModelRegistry models = new ModelRegistry();
        models.register(new ModelDescriptor("system-local", "test", EnumSet.of(Capability.SYSTEM), 8192,
                0, 0, 0, 0, "none", false, 0.1, 0.1, true));
        ModelProvider provider = new ModelProvider() {
            public String id() { return "test"; }
            public boolean isAvailable() { return false; }
            public Optional<String> generate(String modelId, String system, String input) { return Optional.empty(); }
        };
        SqliteMemoryStore memory = new SqliteMemoryStore(tempDirectory.resolve("memory.db"));
        try {
            ToolRegistry tools = new ToolRegistry();
            tools.register(new SystemTool());
            JarvisCore core = new JarvisCore(new RequestManager(router), router,
                    new pl.jarvis.agents.Coordinator(agents, new ModelManager(models, provider), null),
                    new HardwareManager(), new ModelRouter(),
                    models, new ModelManager(models, provider), new ToolManager(tools, new PermissionManager()), memory,
                    new ValidationManager(), new RecoveryManager(0), new AuditLogger(tempDirectory.resolve("audit.log")));

            Task task = core.handle("Sprawdź RAM i CPU systemu");

            assertEquals(TaskStatus.COMPLETED, task.status(), task.errors().toString());
            assertEquals("system", task.selectedAgent());
            assertTrue(task.toolCalls().contains("system:stats"));
            assertFalse(memory.recent(1).isEmpty());
        } finally {
            memory.close();
        }
    }

    @Test
    void delegatesCodingRequestWhenCoordinatorIncorrectlyAnswersDirectly() {
        Agent coding = new Agent() {
            @Override public String id() { return "coding"; }
            @Override public String name() { return "Coding test agent"; }
            @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.CODING); }
            @Override public Set<String> tools() { return Set.of(); }
            @Override public String execute(TaskContext context) { return "executed by coding agent"; }
        };
        AgentRegistry agents = new AgentRegistry();
        agents.register(coding);
        AgentRouter router = new AgentRouter(agents);
        ModelDescriptor model = new ModelDescriptor("router", "test",
                EnumSet.of(Capability.GENERAL, Capability.CODING), 8192,
                0, 0, 0, 0, "none", true, 0.5, 0.9, true);
        ModelRegistry models = new ModelRegistry();
        models.register(model);
        ModelProvider provider = new ModelProvider() {
            public String id() { return "test"; }
            public boolean isAvailable() { return true; }
            public Optional<String> generate(String modelId, String system, String input) {
                return Optional.of("{\"action\":\"answer\",\"answer\":\"incorrect direct answer\"}");
            }
        };
        SqliteMemoryStore memory = new SqliteMemoryStore(tempDirectory.resolve("coding-memory.db"));
        try {
            ModelManager modelManager = new ModelManager(models, provider);
            JarvisCore core = new JarvisCore(new RequestManager(router), router,
                    new pl.jarvis.agents.Coordinator(agents, modelManager, model),
                    new HardwareManager(), new ModelRouter(), models, modelManager,
                    new ToolManager(new ToolRegistry(), new PermissionManager()), memory,
                    new ValidationManager(), new RecoveryManager(0),
                    new AuditLogger(tempDirectory.resolve("coding-audit.log")));

            Task task = core.handle("Napisz program w Java");

            assertEquals(TaskStatus.COMPLETED, task.status(), task.errors().toString());
            assertEquals("coding", task.selectedAgent());
            assertEquals("executed by coding agent", task.result());
        } finally {
            memory.close();
        }
    }
}
