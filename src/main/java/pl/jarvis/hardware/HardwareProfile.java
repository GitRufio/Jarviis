package pl.jarvis.hardware;

import java.util.List;

public record HardwareProfile(
        String cpu,
        int cpuCores,
        long ramTotalMb,
        long ramAvailableMb,
        String gpu,
        long gpuMemoryTotalMb,
        long gpuMemoryAvailableMb,
        String operatingSystem,
        String architecture,
        List<String> accelerators
) {
    public HardwareProfile {
        accelerators = List.copyOf(accelerators);
    }
}
