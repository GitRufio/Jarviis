package pl.jarvis.core;

import pl.jarvis.agents.AgentRouter;

public final class RequestManager {
    private final AgentRouter agentRouter;

    public RequestManager(AgentRouter agentRouter) {
        this.agentRouter = agentRouter;
    }

    public Task createTask(String input) {
        return new Task(input, agentRouter.inferCapabilities(input));
    }
}
