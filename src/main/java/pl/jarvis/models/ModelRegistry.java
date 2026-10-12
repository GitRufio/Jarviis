package pl.jarvis.models;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ModelRegistry {
    private final List<ModelDescriptor> models = new ArrayList<>();

    public void register(ModelDescriptor descriptor) {
        models.removeIf(model -> model.id().equals(descriptor.id()));
        models.add(descriptor);
    }

    public Optional<ModelDescriptor> find(String id) {
        return models.stream().filter(model -> model.id().equals(id)).findFirst();
    }

    public Collection<ModelDescriptor> all() {
        return models.stream().sorted(Comparator.comparing(ModelDescriptor::id)).toList();
    }
}
