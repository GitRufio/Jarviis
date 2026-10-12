package pl.jarvis.models;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public final class OllamaModelProvider implements ModelProvider {
    /** Jak długo pamiętamy wynik /api/tags, żeby nie strzelać do Ollamy
     *  po kilka razy na jedno zapytanie (np. /v1/models woła isAvailable
     *  dla każdego modelu z osobna). */
    private static final long AVAILABILITY_CACHE_MS = 15_000;

    private final URI baseUri;
    private final HttpClient client;
    private final Duration generateTimeout;
    private volatile long availabilityCheckedAt;
    private volatile boolean availabilityCached;

    public OllamaModelProvider(String baseUrl) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.generateTimeout = Duration.ofSeconds(generateTimeoutSeconds());
    }

    @Override
    public String id() { return "ollama"; }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - availabilityCheckedAt < AVAILABILITY_CACHE_MS) return availabilityCached;
        boolean available = checkAvailability();
        availabilityCached = available;
        availabilityCheckedAt = now;
        return available;
    }

    private boolean checkAvailability() {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Optional<String> generate(String modelId, String systemPrompt, String userInput) {
        return generate(modelId, systemPrompt, userInput, 0);
    }

    @Override
    public Optional<String> generate(String modelId, String systemPrompt, String userInput, int maxTokens) {
        return generate(modelId, systemPrompt, userInput, maxTokens, true);
    }

    @Override
    public Optional<String> generate(String modelId, String systemPrompt, String userInput, int maxTokens, boolean think) {
        String options = maxTokens > 0 ? ",\"options\":{\"num_predict\":%d}".formatted(maxTokens) : "";
        String thinkFlag = think ? "" : ",\"think\":false";
        // keep_alive trzyma model w pamięci - kolejne zadania nie płacą 5-15 s za ładowanie.
        String keepAlive = System.getenv().getOrDefault("JARVIS_OLLAMA_KEEP_ALIVE", "30m");
        String body = "{\"model\":\"%s\",\"stream\":false%s,\"keep_alive\":\"%s\",\"system\":\"%s\",\"prompt\":\"%s\"%s}"
                .formatted(escape(modelId), thinkFlag, escape(keepAlive), escape(systemPrompt), escape(userInput), options);
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/generate"))
                    .timeout(generateTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            Optional<String> answer = JsonResponseExtractor.stringField(response.body(), "response")
                    .filter(text -> !text.isBlank());
            if (answer.isPresent()) return answer;
            // Modele "thinking" (np. qwen3) potrafią zwrócić pusty response
            // z samym tokiem rozumowania - lepsze to niż kompletna cisza.
            return JsonResponseExtractor.stringField(response.body(), "thinking")
                    .filter(text -> !text.isBlank());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static long generateTimeoutSeconds() {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault("JARVIS_OLLAMA_TIMEOUT_S", "60"));
            return Math.min(Math.max(value, 10), 600);
        } catch (NumberFormatException exception) {
            return 60;
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
