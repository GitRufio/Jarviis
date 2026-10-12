package pl.jarvis.models;

import java.util.Map;
import java.util.Optional;

public final class ModelManager {
    private final Map<String, ModelProvider> providers;
    private final ModelRegistry registry;

    public ModelManager(ModelRegistry registry, ModelProvider... providers) {
        this.registry = registry;
        this.providers = java.util.Arrays.stream(providers)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ModelProvider::id, provider -> provider));
    }

    public boolean isAvailable(ModelDescriptor model) {
        return providers.containsKey(model.provider()) && providers.get(model.provider()).isAvailable();
    }

    public Optional<String> generate(ModelDescriptor model, String systemPrompt, String input) {
        return generate(model, systemPrompt, input, 0);
    }

    public Optional<String> generate(ModelDescriptor model, String systemPrompt, String input, int maxTokens) {
        ModelProvider provider = providers.get(model.provider());
        if (provider == null || !provider.isAvailable()) return Optional.empty();
        return provider.generate(model.id(), systemPrompt, input, maxTokens);
    }

    public Optional<String> generate(ModelDescriptor model, String systemPrompt, String input, int maxTokens, boolean think) {
        ModelProvider provider = providers.get(model.provider());
        if (provider == null || !provider.isAvailable()) return Optional.empty();
        return provider.generate(model.id(), systemPrompt, input, maxTokens, think);
    }

    public Optional<ModelDescriptor> descriptor(String modelId) {
        return registry.find(modelId);
    }
}
