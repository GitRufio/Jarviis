package pl.jarvis.agents;

import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskContext;
import pl.jarvis.models.ModelDescriptor;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public final class GeneralAgent implements Agent {
    @Override public String id() { return "general"; }
    @Override public String name() { return "General Agent"; }
    @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.GENERAL); }
    @Override public Set<String> tools() { return Set.of(); }

    @Override
    public String execute(TaskContext context) {
        Optional<ModelDescriptor> model = context.task().selectedModel() == null ? Optional.empty()
                : context.models().descriptor(context.task().selectedModel());
        return model.flatMap(selected -> context.models().generate(selected,
                        "You are JARVIS General Agent. Answer concisely in the user's language.", context.task().userInput()))
                .filter(answer -> !answer.isBlank())
                .orElse("Lokalny model nie jest obecnie dostępny. JARVIS poprawnie przyjął zadanie ogólne, ale do pełnej odpowiedzi uruchom Ollamę i skonfiguruj zgodny model.");
    }
}
