
package pl.jarvis.core;

import pl.jarvis.agents.Agent;
import pl.jarvis.agents.AgentRouter;
import pl.jarvis.agents.Coordinator;
import pl.jarvis.agents.CoordinatorDecision;
import pl.jarvis.hardware.HardwareManager;
import pl.jarvis.hardware.HardwareProfile;
import pl.jarvis.logging.AuditLogger;
import pl.jarvis.memory.MemoryStore;
import pl.jarvis.models.ModelRegistry;
import pl.jarvis.models.ModelRouter;
import pl.jarvis.models.ModelSelection;
import pl.jarvis.models.ModelManager;
import pl.jarvis.recovery.RecoveryManager;
import pl.jarvis.tools.ToolManager;
import pl.jarvis.tools.ToolPermissionException;
import pl.jarvis.validation.ValidationManager;

import java.util.Optional;

public final class JarvisCore {
    private final RequestManager requests;
    private final AgentRouter agentRouter;
    private final Coordinator coordinator;
    private final HardwareManager hardwareManager;
    private final ModelRouter modelRouter;
    private final ModelRegistry modelRegistry;
    private final ModelManager modelManager;
    private final ToolManager tools;
    private final MemoryStore memory;
    private final ValidationManager validation;
    private final RecoveryManager recovery;
    private final AuditLogger audit;

    public JarvisCore(
            RequestManager requests,
            AgentRouter agentRouter,
            Coordinator coordinator,
            HardwareManager hardwareManager,
            ModelRouter modelRouter,
            ModelRegistry modelRegistry,
            ModelManager modelManager,
            ToolManager tools,
            MemoryStore memory,
            ValidationManager validation,
            RecoveryManager recovery,
            AuditLogger audit
    ) {
        this.requests = requests;
        this.agentRouter = agentRouter;
        this.coordinator = coordinator;
        this.hardwareManager = hardwareManager;
        this.modelRouter = modelRouter;
        this.modelRegistry = modelRegistry;
        this.modelManager = modelManager;
        this.tools = tools;
        this.memory = memory;
        this.validation = validation;
        this.recovery = recovery;
        this.audit = audit;
    }

    public Task handle(String input) {
        Task task = requests.createTask(input);
        audit.log("USER", task.userInput());
        task.transitionTo(TaskStatus.PLANNING);

        try {
            // 1. Koordynator decyduje, jak obsłużyć polecenie.
            Optional<CoordinatorDecision> planned =
                    coordinator.decide(task.userInput());

            Agent agent = null;

            if (planned.isPresent()) {
                CoordinatorDecision decision = planned.get();

                switch (decision.action()) {
                    case ANSWER -> {
                        if (task.requiredCapabilities().equals(
                                java.util.EnumSet.of(Capability.GENERAL))) {
                            // Koordynator wygenerował już gotową odpowiedź.
                            task.selectAgent("coordinator");
                            coordinator.modelId().ifPresent(task::selectModel);

                            audit.log(
                                    "COORDINATOR",
                                    "Answer generated directly"
                            );

                            task.transitionTo(TaskStatus.RUNNING);
                            task.setResult(decision.answer());
                        } else {
                            // The coordinator is advisory for specialist requests.
                            // Continue with the capability-selected agent when a
                            // model ignores the delegation contract.
                            audit.log(
                                    "COORDINATOR",
                                    "Ignored direct answer for "
                                            + task.requiredCapabilities()
                            );
                        }
                    }

                    case DELEGATE -> {
                        // Weryfikujemy, czy delegowany agent istnieje.
                        agent = coordinator.resolveAgent(
                                decision.agentId()
                        ).orElse(null);

                        if (agent != null) {
                            audit.log(
                                    "COORDINATOR",
                                    "Delegating to " + agent.id()
                            );
                        }
                    }
                }
            }

            // 2. Jeśli nie ma bezpośredniej odpowiedzi,
            // uruchamiamy wybranego agenta.
            if (task.result() == null) {
                if (agent == null) {
                    // Awaryjny router oparty na wymaganych możliwościach.
                    agent = agentRouter.route(
                            task.requiredCapabilities()
                    );

                    audit.log(
                            "AGENT ROUTER",
                            agent.id() + " (fallback)"
                    );
                }

                task.selectAgent(agent.id());

                HardwareProfile hardware = hardwareManager.detect();

                TaskRequirements requirements = new TaskRequirements(
                        agent.capabilities(),
                        4096,
                        false,
                        true
                );

                Optional<ModelSelection> selection = modelRouter.select(
                        requirements,
                        hardware,
                        modelRegistry,
                        modelManager::isAvailable
                );

                selection.ifPresent(value -> {
                    task.selectModel(value.model().id());
                    audit.log("MODEL ROUTER", value.rationale());
                });

                task.transitionTo(TaskStatus.RUNNING);

                int attempts = 0;

                while (true) {
                    try {
                        task.setResult(
                                agent.execute(
                                        new TaskContext(
                                                task,
                                                hardware,
                                                modelManager,
                                                tools
                                        )
                                )
                        );
                        break;

                    } catch (ToolPermissionException exception) {
                        task.addError(exception.getMessage());
                        task.transitionTo(TaskStatus.WAITING_PERMISSION);

                        audit.log(
                                "PERMISSION",
                                exception.getMessage()
                        );

                        return task;

                    } catch (Exception exception) {
                        task.addError(
                                exception.getMessage() == null
                                        ? exception.getClass().getSimpleName()
                                        : exception.getMessage()
                        );

                        if (!recovery.mayRetry(attempts++)) {
                            throw exception;
                        }

                        audit.log(
                                "RECOVERY",
                                "Retrying task " + task.id()
                        );
                    }
                }
            }

            // 3. Obie ścieżki kończą się tą samą walidacją.
            ValidationManager.ValidationResult valid =
                    validation.validate(task);

            if (!valid.valid()) {
                throw new IllegalStateException(valid.reason());
            }

            task.transitionTo(TaskStatus.COMPLETED);
            memory.save("task_result", task.result(), 3);

            audit.log(
                    "RESULT",
                    "Task " + task.id() + " completed"
            );

        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();

            if (task.errors().isEmpty()
                    || !message.equals(task.errors().getLast())) {
                task.addError(message);
            }

            task.transitionTo(TaskStatus.FAILED);

            audit.log(
                    "ERROR",
                    "Task " + task.id() + ": "
                            + task.errors().getLast()
            );
        }

        return task;
    }
}
