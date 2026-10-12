package pl.jarvis.hardware;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class HardwareManager {
    public HardwareProfile detect() {
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long totalRamMb = bytesToMb(osBean.getTotalMemorySize());
        // getFreeMemorySize() nie liczy pamięci podręcznej/buforów - na Linuxie bierzemy
        // MemAvailable z /proc/meminfo (pamięć realnie dostępna dla programów).
        long availableRamMb = memAvailableMb().orElseGet(() -> bytesToMb(osBean.getFreeMemorySize()));
        List<String> accelerators = detectAccelerators();

        GpuInfo gpu = detectNvidiaGpu();
        String gpuName;
        long gpuTotalMb;
        long gpuFreeMb;
        if (gpu != null) {
            gpuName = gpu.name();
            gpuTotalMb = gpu.totalMb();
            gpuFreeMb = gpu.freeMb();
            if (!accelerators.contains("CUDA")) accelerators.add("CUDA");
        } else {
            gpuName = "not detected";
            gpuTotalMb = 0;
            gpuFreeMb = 0;
        }

        return new HardwareProfile(
                System.getProperty("os.arch", "unknown") + " CPU",
                Runtime.getRuntime().availableProcessors(),
                totalRamMb,
                availableRamMb,
                gpuName,
                gpuTotalMb,
                gpuFreeMb,
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""),
                System.getProperty("os.arch", "unknown"),
                accelerators
        );
    }

    private List<String> detectAccelerators() {
        List<String> accelerators = new ArrayList<>();
        if (System.getenv("CUDA_VISIBLE_DEVICES") != null) accelerators.add("CUDA");
        if (System.getenv("ROCR_VISIBLE_DEVICES") != null) accelerators.add("ROCm");
        return accelerators;
    }

    /** Wykrywa kartę NVIDIA przez nvidia-smi. Zwraca null, gdy narzędzia
     *  brak, timeout minie albo parsowanie się nie powiedzie - wtedy
     *  Jarvis działa w trybie CPU bez blokowania startu. */
    private GpuInfo detectNvidiaGpu() {
        try {
            Process process = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=name,memory.total,memory.free", "--format=csv,noheader")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // Format: "NVIDIA GeForce RTX 5060, 8151 MiB, 1882 MiB"
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String name = parts[0].trim();
                long total = parseMiB(parts[1]);
                long free = parseMiB(parts[2]);
                if (name.isEmpty() || total <= 0) continue;
                return new GpuInfo(name, total, free);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private long parseMiB(String text) {
        try {
            return Long.parseLong(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private record GpuInfo(String name, long totalMb, long freeMb) { }

    private long bytesToMb(long bytes) {
        return Math.max(0, bytes / (1024 * 1024));
    }

    /** Odczyt MemAvailable (kB) z /proc/meminfo. Puste, gdy niedostępne. */
    private java.util.Optional<Long> memAvailableMb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    long kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    return java.util.Optional.of(Math.max(0, kb / 1024));
                }
            }
        } catch (Exception ignored) {
            // fallback do getFreeMemorySize()
        }
        return java.util.Optional.empty();
    }
}
