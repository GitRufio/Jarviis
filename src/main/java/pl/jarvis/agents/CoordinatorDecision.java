package pl.jarvis.agents;

public record CoordinatorDecision(
        Action action,
        String answer,
        String agentId
) {
    public enum Action {
        ANSWER,
        DELEGATE
    }

    public CoordinatorDecision {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }

        if (action == Action.ANSWER
                && (answer == null || answer.isBlank())) {
            throw new IllegalArgumentException(
                    "Answer cannot be blank"
            );
        }

        if (action == Action.DELEGATE
                && (agentId == null || agentId.isBlank())) {
            throw new IllegalArgumentException(
                    "Agent ID cannot be blank"
            );
        }
    }
}