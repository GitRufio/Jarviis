package pl.jarvis.models;

import java.util.Optional;

public interface ModelProvider {
    String id();
    boolean isAvailable();
    Optional<String> generate(String modelId, String systemPrompt, String userInput);

    /** Wariant z limitem tokenów do krótkich decyzji (routing, klasyfikacja).
     *  Domyślnie ignoruje limit - dostawcy mogą go obsłużyć dla szybkości. */
    default Optional<String> generate(String modelId, String systemPrompt, String userInput, int maxTokens) {
        return generate(modelId, systemPrompt, userInput);
    }

    /** Jak wyżej, plus flaga think (false wyłącza tok rozumowania w modelach
     *  typu qwen3 - decyzja ma być natychmiastowa, nie mądra). */
    default Optional<String> generate(String modelId, String systemPrompt, String userInput, int maxTokens, boolean think) {
        return generate(modelId, systemPrompt, userInput, maxTokens);
    }
}
