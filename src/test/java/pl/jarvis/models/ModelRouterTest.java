package pl.jarvis.models;

import org.junit.jupiter.api.Test;
import pl.jarvis.core.Capability;
import pl.jarvis.core.TaskRequirements;
import pl.jarvis.hardware.HardwareProfile;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRouterTest {
    @Test
    void selectsModelThatFitsAvailableRatherThanTotalMemory() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(model("small", 4096, 0, 0.5));
        registry.register(model("large", 8192, 0, 0.9));
        HardwareProfile hardware = new HardwareProfile("CPU", 8, 16384, 6000, "none", 0, 0, "Linux", "amd64", List.of());

        ModelSelection selected = new ModelRouter().select(
                new TaskRequirements(EnumSet.of(Capability.CODING), 4096, false, true), hardware, registry).orElseThrow();

        assertEquals("small", selected.model().id());
    }

    @Test
    void rejectsCloudModelWhenLocalOnlyIsSet() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(new ModelDescriptor("cloud", "cloud", EnumSet.of(Capability.GENERAL), 8192,
                0, 0, 0, 0, "n/a", false, 1, 1, false));
        HardwareProfile hardware = new HardwareProfile("CPU", 8, 16000, 16000, "none", 0, 0, "Linux", "amd64", List.of());

        assertTrue(new ModelRouter().select(
                new TaskRequirements(EnumSet.of(Capability.GENERAL), 1, false, true), hardware, registry).isEmpty());
    }

    private ModelDescriptor model(String id, long minRam, long minVram, double quality) {
        return new ModelDescriptor(id, "test", EnumSet.of(Capability.CODING), 8192,
                minRam, minRam, minVram, minVram, "Q4", false, quality, 0.5, true);
    }

    @Test
    void prefersReadableModelOverTinyOneWhenAlternativeFits() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(new ModelDescriptor("tiny", "test", EnumSet.of(Capability.GENERAL), 8192,
                512, 1024, 0, 0, "Q4", false, 0.50, 0.99, true));
        registry.register(new ModelDescriptor("solid", "test", EnumSet.of(Capability.GENERAL), 8192,
                4096, 8192, 0, 0, "Q4", false, 0.62, 0.90, true));
        HardwareProfile hardware = new HardwareProfile("CPU", 8, 32768, 24000, "GPU", 8192, 512,
                "Linux", "amd64", List.of());

        ModelSelection selected = new ModelRouter().select(
                new TaskRequirements(EnumSet.of(Capability.GENERAL), 4096, false, true), hardware, registry).orElseThrow();

        assertEquals("solid", selected.model().id());
    }

    @Test
    void prefersSmallerModelWhenVramIsBusy() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(new ModelDescriptor("giant", "test", EnumSet.of(Capability.GENERAL), 32768,
                16000, 20000, 0, 18000, "Q4", false, 0.95, 0.70, true));
        registry.register(new ModelDescriptor("mid", "test", EnumSet.of(Capability.GENERAL), 32768,
                4000, 7000, 0, 5000, "Q4", false, 0.62, 0.92, true));
        // VRAM zajęty (wolne 1 GB): żaden nie mieści się w pełni na GPU.
        HardwareProfile hardware = new HardwareProfile("CPU", 8, 32768, 24000, "GPU", 8192, 1024,
                "Linux", "amd64", List.of());

        ModelSelection selected = new ModelRouter().select(
                new TaskRequirements(EnumSet.of(Capability.GENERAL), 4096, false, true), hardware, registry).orElseThrow();

        assertEquals("mid", selected.model().id());
    }

    @Test
    void acceptsTinyModelWhenNothingElseFits() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(new ModelDescriptor("tiny", "test", EnumSet.of(Capability.GENERAL), 8192,
                512, 1024, 0, 0, "Q4", false, 0.50, 0.99, true));
        registry.register(new ModelDescriptor("big", "test", EnumSet.of(Capability.GENERAL), 8192,
                60000, 60000, 0, 0, "Q4", false, 0.95, 0.70, true));
        HardwareProfile hardware = new HardwareProfile("CPU", 4, 8192, 2000, "none", 0, 0,
                "Linux", "amd64", List.of());

        ModelSelection selected = new ModelRouter().select(
                new TaskRequirements(EnumSet.of(Capability.GENERAL), 4096, false, true), hardware, registry).orElseThrow();

        assertEquals("tiny", selected.model().id());
    }
}
