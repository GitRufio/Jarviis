package pl.jarvis.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public final class Task {
    private final UUID id = UUID.randomUUID();
    private final String userInput;
    private final Instant createdAt = Instant.now();
    private final EnumSet<Capability> requiredCapabilities;
    private final List<String> toolCalls = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private TaskStatus status = TaskStatus.CREATED;
    private String selectedAgent;
    private String selectedModel;
    private String result;

    public Task(String userInput, EnumSet<Capability> requiredCapabilities) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("Task input must not be blank");
        }
        this.userInput = userInput.trim();
        this.requiredCapabilities = requiredCapabilities.clone();
    }

    public UUID id() { return id; }
    public String userInput() { return userInput; }
    public Instant createdAt() { return createdAt; }
    public EnumSet<Capability> requiredCapabilities() { return requiredCapabilities.clone(); }
    public TaskStatus status() { return status; }
    public String selectedAgent() { return selectedAgent; }
    public String selectedModel() { return selectedModel; }
    public String result() { return result; }
    public List<String> toolCalls() { return List.copyOf(toolCalls); }
    public List<String> errors() { return List.copyOf(errors); }

    public void transitionTo(TaskStatus next) {
        if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Terminal task cannot change status");
        }
        status = next;
    }

    public void selectAgent(String agentId) { selectedAgent = agentId; }
    public void selectModel(String modelId) { selectedModel = modelId; }
    public void addToolCall(String toolId) { toolCalls.add(toolId); }
    public void setResult(String value) { result = value; }
    public void addError(String value) { errors.add(value); }
}
