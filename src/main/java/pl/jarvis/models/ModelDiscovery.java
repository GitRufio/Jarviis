package pl.jarvis.models;

import pl.jarvis.core.Capability;
import pl.jarvis.hardware.HardwareProfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModelDiscovery {

    private ModelDiscovery() { }

    /** Pobiera listę modeli z Ollamy i buduje deskryptory na podstawie
     *  RZECZYWISTYCH parametrów każdego modelu (rozmiar, kontekst, kwantyzacja).
     *  Zwraca pustą mapę, gdy Ollama jest nieosiągalna. */
    public static Map<String, ModelDescriptor> discoverModels(String ollamaUrl) {
        Map<String, ModelDescriptor> result = new LinkedHashMap<>();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("Ollama /api/tags returned HTTP " + response.statusCode());
                return result;
            }
            for (RawModel raw : parseModels(response.body())) {
                try {
                    ModelDescriptor md = descriptorFor(raw.name(), raw.sizeBytes(), raw.parameterSize(),
                            raw.contextLength(), raw.quantization());
                    if (md != null) result.put(md.id(), md);
                } catch (IllegalArgumentException e) {
                    System.out.println("  Pomijam model " + raw.name() + ": " + e.getMessage());
                }
            }
            System.out.println("Wykryto " + result.size() + " modeli w Ollamie");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("Nie można połączyć się z Ollamą pod " + ollamaUrl + " (" + exception.getMessage() + ")");
        }
        return result;
    }

    /** Surowy wpis modelu wyciągnięty z odpowiedzi /api/tags. */
    record RawModel(String name, long sizeBytes, String parameterSize, Integer contextLength, String quantization) { }

    /** Rozbija odpowiedź /api/tags na listę surowych modeli.
     *  Każdy obiekt jest wycinany z liczeniem klamer, więc zagnieżdżone
     *  "details" nie psują parsowania (czysta funkcja, testowalna). */
    static List<RawModel> parseModels(String body) {
        List<RawModel> result = new ArrayList<>();
        if (body == null) return result;
        int modelsIdx = body.indexOf("\"models\"");
        if (modelsIdx < 0) return result;
        int arrayStart = body.indexOf('[', modelsIdx);
        if (arrayStart < 0) return result;
        int arrayEnd = findMatching(body, arrayStart, '[', ']');
        if (arrayEnd < 0) return result;
        String array = body.substring(arrayStart + 1, arrayEnd);

        int idx = 0;
        while (idx < array.length()) {
            int objStart = array.indexOf('{', idx);
            if (objStart < 0) break;
            int objEnd = findMatching(array, objStart, '{', '}');
            if (objEnd < 0) break;
            String obj = array.substring(objStart, objEnd + 1);
            String name = extractString(obj, "name");
            if (name != null && !name.isBlank()) {
                String details = extractObject(obj, "details");
                result.add(new RawModel(
                        name,
                        extractLong(obj, "size", 0),
                        details == null ? null : extractString(details, "parameter_size"),
                        details == null ? null : extractInt(details, "context_length"),
                        details == null ? null : extractString(details, "quantization_level")));
            }
            idx = objEnd + 1;
        }
        return result;
    }

    /** Buduje deskryptor modelu na podstawie jego WŁASNYCH parametrów
     *  (czysta funkcja, testowalna). Wymagania RAM liczone z rzeczywistego
     *  rozmiaru pobranego pliku modelu. */
    public static ModelDescriptor descriptorFor(String name, long sizeBytes, String parameterSize,
                                                Integer contextLength, String quantization) {
        if (name == null || name.isBlank()) return null;
        String lowerName = name.toLowerCase(Locale.ROOT);

        EnumSet<Capability> capabilities = EnumSet.of(Capability.GENERAL);
        if (lowerName.contains("code") || lowerName.contains("coder")) capabilities.add(Capability.CODING);
        // Research w MVP to analiza dostarczonych treści - potrafi to każdy model ogólny.
        capabilities.add(Capability.RESEARCH);

        long sizeMb = Math.max(1, sizeBytes / (1024 * 1024));
        Double paramB = parseParamB(parameterSize);
        int ctx = (contextLength != null && contextLength > 0) ? contextLength : 32768;
        String quant = (quantization == null || quantization.isBlank()) ? "Q4" : quantization;

        double qualityScore;
        double speedScore;
        if (paramB != null) {
            double capped = Math.min(paramB, 30.0);
            qualityScore = 0.5 + capped * 0.015;
            speedScore = 1.0 - capped * 0.01;
        } else {
            qualityScore = 0.55;
            speedScore = 0.8;
        }

        return new ModelDescriptor(
                name,
                "ollama",
                capabilities,
                ctx,
                sizeMb + 512,   // minimum: waga modelu + narzut na kontekst
                sizeMb + 2048,  // rekomendowane: waga + swoboda na kontekst i system
                0,              // GGUF może pracować na samym RAM (offload częściowy)
                sizeMb,         // pełny offload na GPU wymaga ~rozmiaru modelu w VRAM
                quant,
                toolCalling(lowerName),
                qualityScore,
                speedScore,
                true);
    }

    /** Wybiera najlepszy model dla danego zestawu capabilities z uwzględnieniem
     *  sprzętu: odrzuca modele nie mieszczące się w RAM, preferuje modele
     *  wystarczająco szybkie (speed >= 0.8), a wśród nich najlepsze jakościowo.
     *  Dzięki temu na słabszym sprzęcie Jarvis sięga po mniejsze modele
     *  zamiast wieszać się na 30B gigantach. */
    public static ModelDescriptor bestModelFor(
            EnumSet<Capability> capabilities,
            HardwareProfile hardware,
            Map<String, ModelDescriptor> allModels) {

        if (allModels.isEmpty()) return null;

        List<ModelDescriptor> fitting = allModels.values().stream()
                .filter(model -> model.capabilities().containsAll(capabilities))
                .filter(model -> model.contextLength() >= 4096)
                .filter(model -> model.minimumRamMb() <= hardware.ramAvailableMb())
                .toList();
        if (fitting.isEmpty()) return null;

        List<ModelDescriptor> fastEnough = fitting.stream()
                .filter(model -> model.speedScore() >= 0.8)
                .toList();
        List<ModelDescriptor> pool = fastEnough.isEmpty() ? fitting : fastEnough;

        return pool.stream()
                .max(Comparator.comparingDouble(ModelDescriptor::qualityScore)
                        .thenComparingDouble(ModelDescriptor::speedScore)
                        .thenComparingLong(model -> -model.recommendedRamMb()))
                .orElse(null);
    }

    /** Proponuje tag modelu do pobrania, gdy żaden zainstalowany model nie pasuje
     *  do danego agenta. Wariant dobierany do wolnego RAM (małe tagi < 2 GB
     *  na słabsze maszyny, 7-8B na mocniejsze). Zwraca pusty Optional dla
     *  agentów niewymagających modelu (SYSTEM działa na toolach). */
    public static Optional<String> suggestPullFor(EnumSet<Capability> capabilities, HardwareProfile hardware) {
        if (capabilities.contains(Capability.SYSTEM) && capabilities.size() == 1) return Optional.empty();
        boolean bigRam = hardware.ramAvailableMb() >= 16384;
        if (capabilities.contains(Capability.CODING)) {
            return Optional.of(bigRam ? "qwen2.5-coder:7b" : "qwen2.5-coder:1.5b");
        }
        return Optional.of(bigRam ? "qwen3:8b" : "qwen3:1.7b");
    }

    /** Pobiera model do Ollamy (POST /api/pull). Używane tylko za wyraźną zgodą
     *  przez flagę JARVIS_AUTO_PULL=1 - w przeciwnym razie Application
     *  jedynie loguje hint "ollama pull <tag>". */
    public static boolean pullModel(String ollamaUrl, String modelName) {
        try {
            String normalizedBase = ollamaUrl.endsWith("/")
                    ? ollamaUrl.substring(0, ollamaUrl.length() - 1) : ollamaUrl;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            String payload = "{\"name\":\"%s\",\"stream\":false}".formatted(modelName.replace("\"", ""));
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBase + "/api/pull"))
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("Pobieranie modelu " + modelName + " nie powiodło się: " + exception.getMessage());
            return false;
        }
    }

    public static boolean autoPullEnabled() {
        String value = System.getenv("JARVIS_AUTO_PULL");
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes"));
    }

    // ---------- parsowanie JSON bez zewnętrznych zależności ----------

    /** Zwraca indeks znaku zamykającego pasującego do otwierającego na pozycji start. */
    private static int findMatching(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Integer extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    private static long extractLong(String json, String key, long defaultValue) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) return defaultValue;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return defaultValue; }
    }

    /** Wycina surową zawartość obiektu spod klucza (bez zewnętrznych klamer). */
    private static String extractObject(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) return null;
        int open = m.end() - 1;
        int close = findMatching(json, open, '{', '}');
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    /** "30.5B" -> 30.5, "494.03M" -> ~0.494, null/śmieci -> null. */
    static Double parseParamB(String parameterSize) {
        if (parameterSize == null) return null;
        String s = parameterSize.trim().toUpperCase(Locale.ROOT);
        try {
            if (s.endsWith("B") && !s.endsWith("MB")) {
                return Double.parseDouble(s.substring(0, s.length() - 1).trim());
            }
            if (s.endsWith("M")) {
                return Double.parseDouble(s.substring(0, s.length() - 1).trim()) / 1000.0;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean toolCalling(String lowerName) {
        return lowerName.contains("coder") || lowerName.contains("instruct") || lowerName.contains("tool");
    }
}
