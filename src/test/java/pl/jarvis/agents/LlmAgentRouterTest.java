package pl.jarvis.agents;

import org.junit.jupiter.api.Test;
import pl.jarvis.core.Capability;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;
import pl.jarvis.models.ModelProvider;
import pl.jarvis.models.ModelRegistry;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAgentRouterTest {

    private AgentRegistry agents() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(new GeneralAgent());
        registry.register(new SystemAgent());
        registry.register(new CodingAgent());
        registry.register(new ResearchAgent());
        return registry;
    }

    private ModelDescriptor routerModel() {
        return new ModelDescriptor("router", "test", EnumSet.of(Capability.GENERAL), 8192,
                0, 0, 0, 0, "none", false, 0.5, 0.9, true);
    }

    private ModelManager managerReturning(String answer) {
        ModelRegistry models = new ModelRegistry();
        models.register(routerModel());
        ModelProvider provider = new ModelProvider() {
            public String id() { return "test"; }
            public boolean isAvailable() { return true; }
            public Optional<String> generate(String modelId, String system, String input) {
                return Optional.ofNullable(answer);
            }
        };
        return new ModelManager(models, provider);
    }

    @Test
    void routesByModelAnswer() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning("{\"agent\":\"coding\"}"), routerModel());

        assertEquals("coding", router.route("napisz program").orElseThrow().id());
    }

    @Test
    void fallsBackOnGarbageAnswer() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning("nie wiem"), routerModel());

        assertTrue(router.route("cokolwiek").isEmpty());
    }

    @Test
    void fallsBackOnProviderFailure() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning(null), routerModel());

        assertTrue(router.route("cokolwiek").isEmpty());
    }

    @Test
    void mapsInventedVariantToKnownAgent() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning("{\"agent\": \"system_monitor\"}"), routerModel());

        assertEquals("system", router.route("sprawdz RAM").orElseThrow().id());
    }

    @Test
    void rejectsUnknownAgent() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning("{\"agent\": \"plumber\"}"), routerModel());

        assertTrue(router.route("cokolwiek").isEmpty());
    }

    @Test
    void disabledWithoutModel() {
        LlmAgentRouter router = new LlmAgentRouter(agents(), managerReturning("{\"agent\":\"coding\"}"), null);

        assertTrue(router.route("napisz program").isEmpty());
    }
}
