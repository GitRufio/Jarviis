package pl.jarvis.tools;

import pl.jarvis.security.RiskLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public final class FileTool implements Tool {
    private final Path allowedRoot;

    public FileTool(Path allowedRoot) {
        try {
            this.allowedRoot = allowedRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Allowed workspace root must exist", exception);
        }
    }

    @Override
    public String id() { return "file"; }

    @Override
    public String description() { return "Reads, lists, or writes files only under the configured workspace root."; }

    @Override
    public String schema() { return "{action: read|list|write, path: relative path, content?: string}"; }

    @Override
    public RiskLevel riskLevel(Map<String, String> arguments) {
        return "write".equals(arguments.get("action")) ? RiskLevel.CAUTION : RiskLevel.SAFE;
    }

    @Override
    public ToolResult execute(Map<String, String> arguments) throws ToolException {
        String action = arguments.getOrDefault("action", "read");
        Path path = resolve(arguments.getOrDefault("path", "."));
        try {
            return switch (action) {
                case "read" -> new ToolResult(Files.readString(path, StandardCharsets.UTF_8));
                case "list" -> list(path);
                case "write" -> {
                    Files.writeString(path, arguments.getOrDefault("content", ""), StandardCharsets.UTF_8);
                    yield new ToolResult("Written: " + allowedRoot.relativize(path));
                }
                default -> throw new ToolException("Unsupported file action: " + action);
            };
        } catch (IOException exception) {
            throw new ToolException("File operation failed", exception);
        }
    }

    private ToolResult list(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            String content = files.map(item -> item.getFileName().toString())
                    .sorted().reduce("", (left, right) -> left + right + "\n");
            return new ToolResult(content);
        }
    }

    private Path resolve(String candidate) throws ToolException {
        if (candidate == null || candidate.isBlank()) {
            throw new ToolException("Path cannot be blank");
        }

        Path supplied;
        try {
            supplied = Path.of(candidate);
        } catch (RuntimeException exception) {
            throw new ToolException("Invalid file path", exception);
        }

        if (supplied.isAbsolute()) {
            throw new ToolException("Absolute paths are not allowed");
        }

        Path resolved = allowedRoot.resolve(supplied).normalize();
        if (!resolved.startsWith(allowedRoot)) {
            throw new ToolException("Path escapes allowed workspace");
        }

        Path current = allowedRoot;
        for (Path component : allowedRoot.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new ToolException("Symbolic links are not allowed");
            }
        }
        return resolved;
    }
}
