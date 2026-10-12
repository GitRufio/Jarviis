package pl.jarvis.agents;

import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskContext;
import pl.jarvis.models.ModelDescriptor;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public final class ResearchAgent implements Agent {
    @Override public String id() { return "research"; }
    @Override public String name() { return "Research Agent"; }
    @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.RESEARCH); }
    @Override public Set<String> tools() { return Set.of(); }

    @Override
    public String execute(TaskContext context) {
        Optional<ModelDescriptor> model = context.task().selectedModel() == null ? Optional.empty()
                : context.models().descriptor(context.task().selectedModel());
        return model.flatMap(selected -> context.models().generate(selected,
                        "You are JARVIS Research Agent. State uncertainty, distinguish facts from inference, and ask for sources when browsing is unavailable.",
                        context.task().userInput()))
                .filter(answer -> !answer.isBlank())
                .orElse("Nie wybrano dostępnego lokalnego modelu badawczego. Ten MVP nie wykonuje jeszcze dostępu do Internetu; podaj źródła lub uruchom lokalny model do analizy dostarczonych treści.");
    }
}
