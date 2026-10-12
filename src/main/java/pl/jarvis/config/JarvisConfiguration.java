package pl.jarvis.config;

import java.nio.file.Path;

public record JarvisConfiguration(int port, String ollamaUrl, Path dataDirectory, boolean neverCloud) {
    public static JarvisConfiguration fromEnvironment() {
        int port = parsePort(System.getenv("JARVIS_PORT"));
        String ollamaUrl = System.getenv().getOrDefault("JARVIS_OLLAMA_URL", "http://localhost:11434");
        String dataPath = System.getenv().getOrDefault("JARVIS_DATA_DIR", "data");
        return new JarvisConfiguration(port, ollamaUrl, Path.of(dataPath).toAbsolutePath(), true);
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) return 8080;
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port < 65536 ? port : 8080;
        } catch (NumberFormatException exception) {
            return 8080;
        }
    }
}
