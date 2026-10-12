package pl.jarvis.tools;

import com.sun.management.OperatingSystemMXBean;
import pl.jarvis.security.RiskLevel;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.Map;

public final class SystemTool implements Tool {
    @Override
    public String id() { return "system"; }

    @Override
    public String description() { return "Reads local CPU, RAM, operating system and disk usage."; }

    @Override
    public String schema() { return "{action: stats}"; }

    @Override
    public RiskLevel riskLevel(Map<String, String> arguments) { return RiskLevel.SAFE; }

    @Override
    public ToolResult execute(Map<String, String> arguments) throws ToolException {
        String action = arguments.getOrDefault("action", "stats");
        if (!"stats".equals(action)) throw new ToolException("Unsupported system action: " + action);

        OperatingSystemMXBean bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long totalMb = bean.getTotalMemorySize() / (1024 * 1024);
        long freeMb = bean.getFreeMemorySize() / (1024 * 1024);
        long diskFree = 0;
        long diskTotal = 0;
        int inspectedDisks = 0;
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            try {
                diskFree += store.getUsableSpace();
                diskTotal += store.getTotalSpace();
                inspectedDisks++;
            } catch (IOException | SecurityException ignored) {
                // Pseudo-filesystems can legitimately reject capacity queries.
            }
        }
        String diskSummary = inspectedDisks == 0 ? "disk capacity unavailable" : "%d GB free / %d GB total"
                .formatted(diskFree / (1024L * 1024 * 1024), diskTotal / (1024L * 1024 * 1024));
        String result = "System: %s %s; CPU cores: %d; RAM: %d MB available / %d MB total; disks: %s."
                .formatted(System.getProperty("os.name"), System.getProperty("os.version"),
                        Runtime.getRuntime().availableProcessors(), freeMb, totalMb,
                        diskSummary);
        return new ToolResult(result);
    }
}
