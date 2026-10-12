package pl.jarvis.core;

import java.util.EnumSet;

public record TaskRequirements(
        EnumSet<Capability> capabilities,
        int minimumContextLength,
        boolean needsToolCalling,
        boolean localOnly
) {
    public TaskRequirements {
        capabilities = capabilities.clone();
        if (minimumContextLength < 0) {
            throw new IllegalArgumentException("minimumContextLength must be non-negative");
        }
    }
}
