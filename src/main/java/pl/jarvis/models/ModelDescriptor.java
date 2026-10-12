package pl.jarvis.models;

import pl.jarvis.core.Capability;

import java.util.EnumSet;

public record ModelDescriptor(
        String id,
        String provider,
        EnumSet<Capability> capabilities,
        int contextLength,
        long minimumRamMb,
        long recommendedRamMb,
        long minimumVramMb,
        long recommendedVramMb,
        String quantization,
        boolean toolCalling,
        double qualityScore,
        double speedScore,
        boolean local
) {
    public ModelDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Model id is required");
        capabilities = capabilities.clone();
    }
}
