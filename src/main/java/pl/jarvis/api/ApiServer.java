package pl.jarvis.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pl.jarvis.core.Task;
import pl.jarvis.core.JarvisCore;
import pl.jarvis.hardware.HardwareManager;
import pl.jarvis.hardware.HardwareProfile;
import pl.jarvis.memory.MemoryEntry;
import pl.jarvis.memory.MemoryStore;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelManager;
import pl.jarvis.models.ModelRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ApiServer implements AutoCloseable {
    private final HttpServer server;
    private final JarvisCore core;
    private final HardwareManager hardware;
    private final ModelRegistry models;
    private final ModelManager modelManager;
    private final MemoryStore memory;

    public ApiServer(int port, JarvisCore core, HardwareManager hardware, ModelRegistry models,
                     ModelManager modelManager, MemoryStore memory) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.core = core;
        this.hardware = hardware;
        this.models = models;
        this.modelManager = modelManager;
        this.memory = memory;
        server.createContext("/health", this::health);
        server.createContext("/v1/hardware", this::hardware);
        server.createContext("/v1/models", this::models);
        server.createContext("/v1/tasks", this::tasks);
        server.createContext("/v1/memory", this::memory);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }

    private void health(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        respond(exchange, 200, "{\"status\":\"ok\",\"service\":\"jarvis\"}");
    }

    private void hardware(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        HardwareProfile profile = hardware.detect();
        String json = "{\"cpu\":%s,\"cpuCores\":%d,\"ramTotalMb\":%d,\"ramAvailableMb\":%d,\"gpu\":%s,\"gpuMemoryTotalMb\":%d,\"gpuMemoryAvailableMb\":%d,\"operatingSystem\":%s,\"architecture\":%s,\"accelerators\":%s}"
                .formatted(Json.string(profile.cpu()), profile.cpuCores(), profile.ramTotalMb(), profile.ramAvailableMb(),
                        Json.string(profile.gpu()), profile.gpuMemoryTotalMb(), profile.gpuMemoryAvailableMb(),
                        Json.string(profile.operatingSystem()), Json.string(profile.architecture()), Json.array(profile.accelerators()));
        respond(exchange, 200, json);
    }

    private void models(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        List<String> result = models.all().stream().map(model -> modelJson(model, modelManager.isAvailable(model))).toList();
        respond(exchange, 200, "{\"models\":" + String.join(",", result).replaceFirst("^", "[").replaceFirst("$", "]") + "}");
    }

    private void tasks(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String input = Json.readString(body, "input").orElse(null);
        if (input == null || input.isBlank()) {
            respond(exchange, 400, "{\"error\":\"JSON field 'input' is required\"}");
            return;
        }
        Task task;
        try {
            task = core.handle(input);
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400,
                    "{\"error\":" + Json.string(exception.getMessage()) + "}");
            return;
        } catch (RuntimeException exception) {
            // Tymczasowa diagnostyka: pokaż rzeczywisty błąd
            // w terminalu, w którym działa backend Java.
            exception.printStackTrace();

            respond(exchange, 500,
                    "{\"error\":" + Json.string(
                            "Internal server error: "
                                    + exception.getClass().getSimpleName()
                    ) + "}");
            return;
        }
        int status = task.status().name().equals("COMPLETED") ? 200 : 500;
        respond(exchange, status, taskJson(task));
    }

    private void memory(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        List<String> entries = memory.recent(20).stream().map(this::memoryJson).toList();
        respond(exchange, 200, "{\"entries\":" + String.join(",", entries).replaceFirst("^", "[").replaceFirst("$", "]") + "}");
    }

    private String taskJson(Task task) {
        return "{\"id\":%s,\"status\":%s,\"createdAt\":%s,\"requiredCapabilities\":%s,\"selectedAgent\":%s,\"selectedModel\":%s,\"toolCalls\":%s,\"result\":%s,\"errors\":%s}"
                .formatted(Json.string(task.id().toString()), Json.string(task.status().name()), Json.string(task.createdAt().toString()),
                        Json.array(task.requiredCapabilities().stream().map(Enum::name).sorted().toList()), Json.string(task.selectedAgent()),
                        Json.string(task.selectedModel()), Json.array(task.toolCalls()), Json.string(task.result()), Json.array(task.errors()));
    }

    private String modelJson(ModelDescriptor model, boolean available) {
        return "{\"id\":%s,\"provider\":%s,\"capabilities\":%s,\"contextLength\":%d,\"minimumRamMb\":%d,\"minimumVramMb\":%d,\"toolCalling\":%s,\"local\":%s,\"available\":%s}"
                .formatted(Json.string(model.id()), Json.string(model.provider()),
                        Json.array(model.capabilities().stream().map(Enum::name).sorted().toList()), model.contextLength(),
                        model.minimumRamMb(), model.minimumVramMb(), model.toolCalling(), model.local(), available);
    }

    private String memoryJson(MemoryEntry entry) {
        return "{\"id\":%d,\"type\":%s,\"content\":%s,\"importance\":%d,\"createdAt\":%s,\"updatedAt\":%s}"
                .formatted(entry.id(), Json.string(entry.type()), Json.string(entry.content()), entry.importance(),
                        Json.string(entry.createdAt().toString()), Json.string(entry.updatedAt().toString()));
    }

    private boolean requireMethod(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
        return false;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
