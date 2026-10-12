package pl.jarvis.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class AuditLogger {
    private final Path logFile;

    public AuditLogger(Path logFile) {
        this.logFile = logFile;
    }

    public synchronized void log(String component, String message) {
        String entry = "[%s] [%s] %s%n".formatted(Instant.now(), component, message.replaceAll("[\\r\\n]+", " "));
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("Audit log failure: " + exception.getMessage());
        }
    }
}
