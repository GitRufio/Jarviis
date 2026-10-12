package pl.jarvis.models;

import pl.jarvis.core.TaskRequirements;
import pl.jarvis.hardware.HardwareProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class ModelRouter {
    public Optional<ModelSelection> select(
            TaskRequirements requirements,
            HardwareProfile hardware,
            ModelRegistry registry
    ) {
        return select(requirements, hardware, registry, model -> true);
    }

    public Optional<ModelSelection> select(
            TaskRequirements requirements,
            HardwareProfile hardware,
            ModelRegistry registry,
            Predicate<ModelDescriptor> availability
    ) {
        List<ModelDescriptor> feasible = registry.all().stream()
                .filter(availability)
                .filter(model -> model.capabilities().containsAll(requirements.capabilities()))
                .filter(model -> model.contextLength() >= requirements.minimumContextLength())
                .filter(model -> !requirements.needsToolCalling() || model.toolCalling())
                .filter(model -> !requirements.localOnly() || model.local())
                .filter(model -> model.minimumRamMb() <= hardware.ramAvailableMb())
                .filter(model -> hasEnoughVram(model, hardware))
                .toList();
        // Próg jakości dla odpowiedzi do użytkownika: malutkie modele (< ~4B) potrafią
        // produkować bełkot zamiast odpowiedzi. Stosujemy próg tylko, gdy istnieje
        // lepsza alternatywa - na słabym sprzęcie bierzemy co się da.
        List<ModelDescriptor> pool = feasible.stream()
                .filter(model -> model.qualityScore() >= MIN_USER_QUALITY || !needsReadableAnswer(requirements))
                .toList();
        if (pool.isEmpty()) pool = feasible;
        return pool.stream()
                .map(model -> new ModelSelection(model, score(model, hardware), rationale(model, hardware)))
                .max(Comparator.comparingDouble(ModelSelection::score));
    }

    private static final double MIN_USER_QUALITY = 0.55;

    private boolean needsReadableAnswer(TaskRequirements requirements) {
        return requirements.capabilities().stream().anyMatch(capability ->
                capability == pl.jarvis.core.Capability.GENERAL
                        || capability == pl.jarvis.core.Capability.RESEARCH
                        || capability == pl.jarvis.core.Capability.CODING);
    }

    private boolean hasEnoughVram(ModelDescriptor model, HardwareProfile hardware) {
        return model.minimumVramMb() == 0 || model.minimumVramMb() <= hardware.gpuMemoryAvailableMb();
    }

    private double score(ModelDescriptor model, HardwareProfile hardware) {
        boolean fitsVram = model.recommendedVramMb() == 0
                || model.recommendedVramMb() <= hardware.gpuMemoryAvailableMb();
        if (!fitsVram) {
            // Model pójdzie głównie na CPU - tam liczy się szybkość (tok/s ~ 1/rozmiar),
            // więc szybkość dominuje nad jakością, żeby nie wybierać 30B gigantów
            // przy zajętym VRAM.
            return model.qualityScore() * 0.3 + model.speedScore() * 0.7;
        }
        double score = model.qualityScore() * 0.65 + model.speedScore() * 0.35;
        if (model.recommendedRamMb() <= hardware.ramAvailableMb()) score += 0.2;
        score += 0.2;
        return score;
    }

    private String rationale(ModelDescriptor model, HardwareProfile hardware) {
        return "%s spełnia wymagania zadania oraz mieści się w dostępnej pamięci RAM (%d MB) i VRAM (%d MB)."
                .formatted(model.id(), hardware.ramAvailableMb(), hardware.gpuMemoryAvailableMb());
    }
}
