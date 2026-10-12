package pl.jarvis.models;

import org.junit.jupiter.api.Test;
import pl.jarvis.core.Capability;
import pl.jarvis.hardware.HardwareProfile;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiscoveryTest {

    private static final String TAGS = """
            {"models":[
            {"name":"big-coder:latest","model":"big-coder:latest","size":18556700444,
             "details":{"format":"gguf","parameter_size":"30.5B","quantization_level":"Q4_K_M","context_length":262144}},
            {"name":"small-general:1b","model":"small-general:1b","size":397821516,
             "details":{"format":"gguf","parameter_size":"1.7B","quantization_level":"Q4_K_M","context_length":32768}}
            ]}""";

    @Test
    void parsesEachModelWithItsOwnParameters() {
        List<ModelDiscovery.RawModel> models = ModelDiscovery.parseModels(TAGS);

        assertEquals(2, models.size());
        assertEquals("big-coder:latest", models.get(0).name());
        assertEquals(18556700444L, models.get(0).sizeBytes());
        assertEquals("30.5B", models.get(0).parameterSize());
        assertEquals(262144, models.get(0).contextLength());
        assertEquals("small-general:1b", models.get(1).name());
        assertEquals(32768, models.get(1).contextLength());
    }

    @Test
    void buildsRamRequirementsFromRealSizeAndMarksCoderCapabilities() {
        ModelDescriptor coder = ModelDiscovery.descriptorFor("qwen2.5-coder:7b", 6254212807L, "7.6B", 32768, "Q6_K");

        assertTrue(coder.capabilities().containsAll(EnumSet.of(Capability.GENERAL, Capability.CODING, Capability.RESEARCH)));
        assertEquals(32768, coder.contextLength());
        // 5964 MB wagi + narzuty
        assertEquals(5964 + 512, coder.minimumRamMb());
        assertEquals(5964 + 2048, coder.recommendedRamMb());
        assertEquals("Q6_K", coder.quantization());
    }

    @Test
    void prefersFastFittingModelOverGiantOnStrongHardware() {
        Map<String, ModelDescriptor> all = new LinkedHashMap<>();
        all.put("giant", ModelDiscovery.descriptorFor("giant-coder:30b", 18556700444L, "30.5B", 262144, "Q4_K_M"));
        all.put("mid", ModelDiscovery.descriptorFor("mid-coder:7b", 6254212807L, "7.6B", 32768, "Q6_K"));
        HardwareProfile hardware = new HardwareProfile("CPU", 16, 31877, 24000, "RTX 5060", 8151, 1882,
                "Linux", "amd64", List.of("CUDA"));

        ModelDescriptor best = ModelDiscovery.bestModelFor(EnumSet.of(Capability.CODING), hardware, all);

        assertEquals("mid-coder:7b", best.id());
    }

    @Test
    void fallsBackToSmallestWhenRamIsTight() {
        Map<String, ModelDescriptor> all = new LinkedHashMap<>();
        all.put("mid", ModelDiscovery.descriptorFor("mid-coder:7b", 6254212807L, "7.6B", 32768, "Q6_K"));
        all.put("tiny", ModelDiscovery.descriptorFor("tiny-coder:0.5b", 397821516L, "494.03M", 32768, "Q4_K_M"));
        HardwareProfile weak = new HardwareProfile("CPU", 4, 4096, 2000, "not detected", 0, 0,
                "Linux", "amd64", List.of());

        ModelDescriptor best = ModelDiscovery.bestModelFor(EnumSet.of(Capability.CODING), weak, all);

        assertEquals("tiny-coder:0.5b", best.id());
    }

    @Test
    void suggestsPullSizedToHardware() {
        HardwareProfile strong = new HardwareProfile("CPU", 16, 31877, 24000, "RTX", 8000, 2000,
                "Linux", "amd64", List.of());
        HardwareProfile weak = new HardwareProfile("CPU", 4, 4096, 2000, "none", 0, 0,
                "Linux", "amd64", List.of());

        assertEquals(Optional.of("qwen2.5-coder:7b"),
                ModelDiscovery.suggestPullFor(EnumSet.of(Capability.CODING), strong));
        assertEquals(Optional.of("qwen2.5-coder:1.5b"),
                ModelDiscovery.suggestPullFor(EnumSet.of(Capability.CODING), weak));
        assertEquals(Optional.of("qwen3:8b"),
                ModelDiscovery.suggestPullFor(EnumSet.of(Capability.GENERAL), strong));
        assertTrue(ModelDiscovery.suggestPullFor(EnumSet.of(Capability.SYSTEM), strong).isEmpty());
    }
}
