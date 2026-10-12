
package pl.jarvis.tools;

import pl.jarvis.security.RiskLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceFileTool implements Tool {
    private final Path root;

    public WorkspaceFileTool(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        this.root = normalized.toRealPath();
    }

    @Override
    public String id() {
        return "workspace_file";
    }

    @Override
    public String description() {
        return "Reads, lists and writes files inside Jarvis's isolated workspace.";
    }

    @Override
    public String schema() {
        return "{action: read|list|write, path: relative path, content?: string}";
    }

    @Override
    public RiskLevel riskLevel(Map<String, String> arguments) {
        String action = arguments.getOrDefault("action", "read");

        if (!action.equals("read")
                && !action.equals("list")
                && !action.equals("write")) {
            return RiskLevel.BLOCKED;
        }

        try {
            resolve(arguments.getOrDefault("path", "."));
            return RiskLevel.SAFE;
        } catch (ToolException exception) {
            return RiskLevel.BLOCKED;
        }
    }

    @Override
    public ToolResult execute(Map<String, String> arguments)
            throws ToolException {
        String action = arguments.getOrDefault("action", "read");
        String candidate = arguments.getOrDefault("path", ".");
        Path target = resolve(candidate);

        try {
            return switch (action) {
                case "read" -> new ToolResult(
                        Files.readString(target, StandardCharsets.UTF_8)
                );

                case "list" -> list(target);

                case "write" -> {
                    Path parent = target.getParent();

                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    // Sprawdzamy ponownie ścieżkę po utworzeniu katalogów.
                    target = resolve(candidate);

                    if (Files.isDirectory(target)) {
                        throw new ToolException(
                                "Cannot overwrite a directory"
                        );
                    }

                    Files.writeString(
                            target,
                            arguments.getOrDefault("content", ""),
                            StandardCharsets.UTF_8
                    );

                    yield new ToolResult("Written: " + target);
                }

                default -> throw new ToolException(
                        "Unsupported workspace action: " + action
                );
            };
        } catch (IOException exception) {
            throw new ToolException(
                    "Workspace operation failed",
                    exception
            );
        }
    }

    private ToolResult list(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            String result = files
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));

            return new ToolResult(result);
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
            throw new ToolException("Invalid workspace path", exception);
        }

        if (supplied.isAbsolute()) {
            throw new ToolException(
                    "Absolute paths are not allowed"
            );
        }

        Path resolved = root.resolve(supplied).normalize();

        if (!resolved.startsWith(root)) {
            throw new ToolException(
                    "Path escapes the workspace"
            );
        }

        // Nie pozwalamy przechodzić przez dowiązania symboliczne.
        Path current = root;

        for (Path component : root.relativize(resolved)) {
            current = current.resolve(component);

            if (Files.isSymbolicLink(current)) {
                throw new ToolException(
                        "Symbolic links are not allowed in the workspace"
                );
            }
        }

        return resolved;
    }
}
