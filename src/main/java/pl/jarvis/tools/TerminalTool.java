package pl.jarvis.tools;

import pl.jarvis.security.RiskLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class TerminalTool implements Tool {
    private static final Set<String> SAFE_COMMANDS = Set.of("pwd", "ls", "git", "java");
    private static final Set<String> DANGEROUS_COMMANDS = Set.of("sudo", "rm", "mkfs", "dd", "shutdown", "reboot", "poweroff");
    private final Path allowedRoot;
    private final Duration timeout;

    public TerminalTool(Path allowedRoot, Duration timeout) {
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    @Override
    public String id() { return "terminal"; }

    @Override
    public String description() { return "Runs a very small allow-list of commands in the workspace with a timeout."; }

    @Override
    public String schema() { return "{command: string}; shell operators are forbidden"; }

    @Override
    public RiskLevel riskLevel(Map<String, String> arguments) {
        try {
            List<String> tokens = parse(arguments.getOrDefault("command", ""));
            if (tokens.isEmpty()) return RiskLevel.BLOCKED;
            if (DANGEROUS_COMMANDS.contains(tokens.getFirst())) return RiskLevel.DANGEROUS;
            return SAFE_COMMANDS.contains(tokens.getFirst()) ? RiskLevel.SAFE : RiskLevel.BLOCKED;
        } catch (ToolException exception) {
            return RiskLevel.BLOCKED;
        }
    }

    @Override
    public ToolResult execute(Map<String, String> arguments) throws ToolException {
        List<String> tokens = parse(arguments.getOrDefault("command", ""));
        if (riskLevel(arguments) != RiskLevel.SAFE) throw new ToolException("Command is not in the safe allow-list");
        if ("git".equals(tokens.getFirst()) && (tokens.size() < 2 || !Set.of("status", "diff").contains(tokens.get(1)))) {
            throw new ToolException("Only 'git status' and 'git diff' are allowed");
        }
        try {
            Process process = new ProcessBuilder(tokens)
                    .directory(allowedRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ToolException("Command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ToolResult("exit=%d\n%s".formatted(process.exitValue(), output));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException("Command could not be executed", exception);
        }
    }

    private List<String> parse(String command) throws ToolException {
        if (command.isBlank() || command.matches(".*[;&|`$><].*")) {
            throw new ToolException("Shell syntax is not allowed");
        }
        List<String> tokens = new ArrayList<>();
        boolean quoted = false;
        StringBuilder current = new StringBuilder();
        for (char character : command.trim().toCharArray()) {
            if (character == '"') { quoted = !quoted; continue; }
            if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) { tokens.add(current.toString()); current.setLength(0); }
            } else {
                current.append(character);
            }
        }
        if (quoted) throw new ToolException("Unclosed quote");
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }
}
