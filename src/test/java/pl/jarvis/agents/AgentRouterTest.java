package pl.jarvis.agents;

import org.junit.jupiter.api.Test;
import pl.jarvis.core.Capability;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRouterTest {
    private final AgentRouter router = router();

    @Test
    void routesSystemRequest() {
        assertEquals(EnumSet.of(Capability.SYSTEM), router.inferCapabilities("Sprawdź CPU i RAM"));
        assertEquals("system", router.route(router.inferCapabilities("Sprawdź CPU i RAM")).id());
    }

    @Test
    void routesCodingAndResearchRequests() {
        assertEquals("coding", router.route(router.inferCapabilities("Zdebuguj kod Java")).id());
        assertEquals("coding", router.route(router.inferCapabilities("Napisz program w Java")).id());
        assertEquals("research", router.route(router.inferCapabilities("Wyszukaj dokumentację API")).id());
    }

    private AgentRouter router() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(new GeneralAgent());
        registry.register(new SystemAgent());
        registry.register(new CodingAgent());
        registry.register(new ResearchAgent());
        return new AgentRouter(registry);
    }
}
