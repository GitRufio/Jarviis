package pl.jarvis;

import pl.jarvis.agents.AgentRegistry;
import pl.jarvis.agents.AgentRouter;
import pl.jarvis.agents.Coordinator;
import pl.jarvis.agents.CodingAgent;
import pl.jarvis.agents.GeneralAgent;
import pl.jarvis.agents.ResearchAgent;
import pl.jarvis.agents.SystemAgent;
import pl.jarvis.api.ApiServer;
import pl.jarvis.config.JarvisConfiguration;
import pl.jarvis.core.Capability;
import pl.jarvis.core.JarvisCore;
import pl.jarvis.core.RequestManager;
import pl.jarvis.hardware.HardwareManager;
import pl.jarvis.hardware.HardwareProfile;
import pl.jarvis.logging.AuditLogger;
import pl.jarvis.memory.SqliteMemoryStore;
import pl.jarvis.models.ModelDescriptor;
import pl.jarvis.models.ModelDiscovery;
import pl.jarvis.models.ModelManager;
import pl.jarvis.models.ModelRegistry;
import pl.jarvis.models.ModelRouter;
import pl.jarvis.models.OllamaModelProvider;
import pl.jarvis.recovery.RecoveryManager;
import pl.jarvis.security.PermissionManager;
import pl.jarvis.tools.*;
import pl.jarvis.validation.ValidationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Application {
    private Application() { }

    public static void main(String[] args) throws Exception {
        JarvisConfiguration configuration = JarvisConfiguration.fromEnvironment();
        Path dataDir = configuration.dataDirectory();
        Files.createDirectories(dataDir);

        AgentRegistry agents = new AgentRegistry();
        agents.register(new GeneralAgent());
        agents.register(new SystemAgent());
        agents.register(new CodingAgent());
        agents.register(new ResearchAgent());
        AgentRouter agentRouter = new AgentRouter(agents);

        HardwareManager hardware = new HardwareManager();
        ModelRegistry models = discoverAndRegisterModels(configuration.ollamaUrl(), hardware);
        ModelManager modelManager = new ModelManager(models, new OllamaModelProvider(configuration.ollamaUrl()));
        ToolRegistry tools = new ToolRegistry();
        tools.register(new SystemTool());
        tools.register(new FileTool(java.nio.file.Path.of(".").toAbsolutePath()));
        tools.register(new TerminalTool(java.nio.file.Path.of(".").toAbsolutePath(), Duration.ofSeconds(10)));
        tools.register(new WorkspaceFileTool(dataDir.resolve("workspace")));

        SqliteMemoryStore memory = new SqliteMemoryStore(dataDir.resolve("jarvis.db"));
        AuditLogger audit = new AuditLogger(dataDir.resolve("audit.log"));
        Coordinator coordinator = new Coordinator(
                agents,
                modelManager,
                routerModel(models)
        );
        JarvisCore core = new JarvisCore(
                new RequestManager(agentRouter), agentRouter, coordinator, hardware, new ModelRouter(), models, modelManager,
                new ToolManager(tools, new PermissionManager()), memory, new ValidationManager(), new RecoveryManager(1), audit
        );

        ApiServer server = new ApiServer(configuration.port(), core, hardware, models, modelManager, memory);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            memory.close();
        }));
        server.start();
        System.out.printf("JARVIS MVP listening on http://localhost:%d%n", server.port());
    }

    private static ModelRegistry discoverAndRegisterModels(String ollamaUrl, HardwareManager hardware) {
        ModelRegistry registry = new ModelRegistry();
        HardwareProfile profile = hardware.detect();
        System.out.printf("Sprzęt: RAM %d/%d MB, GPU %s (VRAM %d/%d MB)%n",
                profile.ramAvailableMb(), profile.ramTotalMb(), profile.gpu(),
                profile.gpuMemoryAvailableMb(), profile.gpuMemoryTotalMb());

        // Discover available models from Ollama
        Map<String, ModelDescriptor> discovered = ModelDiscovery.discoverModels(ollamaUrl);

        if (discovered.isEmpty()) {
            // Fallback: register minimal defaults if cannot reach Ollama
            // This ensures the app still starts even without Ollama running
            registry.register(new ModelDescriptor(
                    "fallback-general", "ollama", EnumSet.of(Capability.GENERAL), 4096, 2048, 4096, 0, 0, "Q4", false, 0.5, 0.7, true));
            registry.register(new ModelDescriptor(
                    "fallback-coding", "ollama", EnumSet.of(Capability.CODING), 8192, 4096, 8192, 0, 0, "Q4", false, 0.6, 0.5, true));
            System.out.println("Brak modeli w Ollamie - sprawdź czy 'ollama serve' działa i wykonaj np. 'ollama pull qwen3:8b'.");
            return registry;
        }

        // Gdy dla jakiegoś agenta brak pasującego modelu, proponujemy (lub za flagą
        // JARVIS_AUTO_PULL=1 samodzielnie pobieramy) model dobrany do wolnego RAM.
        Map<EnumSet<Capability>, String> agentCaps = Map.of(
                EnumSet.of(Capability.GENERAL), "general",
                EnumSet.of(Capability.CODING), "coding",
                EnumSet.of(Capability.RESEARCH), "research");
        boolean toppedUp = false;
        for (Map.Entry<EnumSet<Capability>, String> entry : agentCaps.entrySet()) {
            if (ModelDiscovery.bestModelFor(entry.getKey(), profile, discovered) != null) continue;
            Optional<String> suggestion = ModelDiscovery.suggestPullFor(entry.getKey(), profile);
            if (suggestion.isEmpty()) continue;
            if (ModelDiscovery.autoPullEnabled()) {
                System.out.printf("Brak modelu dla agenta %s - pobieram %s ...%n", entry.getValue(), suggestion.get());
                if (ModelDiscovery.pullModel(ollamaUrl, suggestion.get())) toppedUp = true;
            } else {
                System.out.printf("Brak modelu dla agenta %s - wykonaj: ollama pull %s (auto: JARVIS_AUTO_PULL=1)%n",
                        entry.getValue(), suggestion.get());
            }
        }
        if (toppedUp) discovered = ModelDiscovery.discoverModels(ollamaUrl);

        // Select best model for each agent type (dobierane do wykrytego sprzętu)
        EnumSet<Capability> generalCaps = EnumSet.of(Capability.GENERAL);
        EnumSet<Capability> codingCaps = EnumSet.of(Capability.CODING);
        EnumSet<Capability> researchCaps = EnumSet.of(Capability.RESEARCH);

        ModelDescriptor generalModel = ModelDiscovery.bestModelFor(generalCaps, profile, discovered);
        ModelDescriptor codingModel = ModelDiscovery.bestModelFor(codingCaps, profile, discovered);
        ModelDescriptor researchModel = ModelDiscovery.bestModelFor(researchCaps, profile, discovered);

        // Register the selected models (skip null)
        if (generalModel != null) registry.register(generalModel);
        if (codingModel != null) registry.register(codingModel);
        if (researchModel != null) registry.register(researchModel);
        System.out.printf("Domyślne modele: general=%s coding=%s research=%s%n",
                idOf(generalModel), idOf(codingModel), idOf(researchModel));

        // Also register any other discovered models that might be useful
        discovered.values().stream()
                .filter(m -> !registry.all().stream().anyMatch(mm -> mm.id().equals(m.id())))
                .limit(8)
                .forEach(registry::register);

        System.out.printf("Discovered %d models from Ollama, registered %d%n", discovered.size(), registry.all().size());
        return registry;
    }

    private static String idOf(ModelDescriptor model) {
        return model == null ? "(brak)" : model.id();
    }

    /** Model do klasyfikacji poleceń: nadpisanie przez JARVIS_ROUTER_MODEL
     *  (musi być zarejestrowany), inaczej najszybszy z sensownych modeli
     *  ogólnych - klasyfikacja ma być szybka, więc celowo NIE bierzemy
     *  największego (30B przy jednym pytaniu potrafił myśleć pół minuty). */
    private static ModelDescriptor routerModel(ModelRegistry models) {
        String override = System.getenv("JARVIS_ROUTER_MODEL");
        if (override != null && !override.isBlank()) {
            Optional<ModelDescriptor> found = models.find(override.trim());
            if (found.isPresent()) return found.get();
            System.out.printf("JARVIS_ROUTER_MODEL=%s nieznany - używam domyślnego.%n", override.trim());
        }
        HardwareProfile hardware = new HardwareManager().detect();
        List<ModelDescriptor> fitting = models.all().stream()
                .filter(model -> model.capabilities().contains(Capability.GENERAL))
                .filter(model -> model.minimumRamMb() <= hardware.ramAvailableMb())
                .toList();
        if (fitting.isEmpty()) return null;
        List<ModelDescriptor> fast = fitting.stream()
                .filter(model -> model.speedScore() >= 0.85)
                .toList();
        List<ModelDescriptor> pool = fast.isEmpty() ? fitting : fast;
        return pool.stream()
                .max(java.util.Comparator.comparingDouble(ModelDescriptor::qualityScore)
                        .thenComparingDouble(ModelDescriptor::speedScore))
                .orElse(null);
    }
}