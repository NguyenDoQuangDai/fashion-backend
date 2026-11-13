package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Test-only wire recorder. The production Gemini adapter still creates and decodes every request. */
final class GeminiBenchmarkTransport implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long SPACING_MS = 6500;
    static final int RPM = 10, TPM = 200_000, RPD = 400, UNKNOWN_INPUT_TOKENS = 65_536;
    private final Path folder;
    private final URI upstream;
    private final HttpServer server;
    private final List<JsonNode> prior;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile String turnKey;
    private volatile String failure;
    final List<JsonNode> current = new ArrayList<>();

    GeminiBenchmarkTransport(Path folder, URI upstream, List<JsonNode> prior) throws Exception {
        this.folder = folder; this.upstream = upstream; this.prior = List.copyOf(prior);
        Files.createDirectories(folder);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String requestHash = sha(JSON.writeValueAsBytes(canonical(JSON.readTree(body))));
                var existing = attempts(turnKey);
                ObjectNode event = null;
                for (JsonNode old : existing) {
                    if (!old.path("requestSha256").asText().equals(requestHash))
                        throw new IllegalStateException("Cached request changed");
                    if (!old.path("completed").asBoolean())
                        throw new IllegalStateException("Uncertain HTTP attempt; preserve checkpoint and reconcile before reissuing");
                    if (old.path("status").asInt() == 200) { event = (ObjectNode) old.deepCopy(); break; }
                }
                boolean replay = event != null;
                if (!replay) {
                    awaitBudget();
                    int attempt = existing.size() + 1;
                    event = JSON.createObjectNode();
                    event.put("turnKey", turnKey); event.put("attempt", attempt);
                    event.put("requestSha256", requestHash); event.put("startedAt", Instant.now().toString());
                    event.put("completed", false);
                    Path target = folder.resolve(turnKey + "-" + attempt + ".json");
                    atomic(target, event); // Reserve quota before the real HTTP call.
                    String key = exchange.getRequestHeaders().getFirst("x-goog-api-key");
                    long start = System.nanoTime();
                    try {
                        var request = HttpRequest.newBuilder(upstream.resolve(exchange.getRequestURI().getPath()))
                            .timeout(Duration.ofSeconds(58)).header("Content-Type", "application/json")
                            .header("x-goog-api-key", key).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        event.put("status", response.statusCode());
                        // Only provider output is persisted; never request headers or credentials.
                        String responseBody = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                        if (key != null && !key.isBlank()) responseBody = responseBody.replace(key, "[REDACTED]");
                        event.put("body", responseBody);
                        event.put("retryAfter", response.headers().firstValue("Retry-After").orElse(""));
                        try {
                            var envelope = JSON.readTree(responseBody);
                            event.set("usageMetadata", envelope.path("usageMetadata"));
                            event.put("modelVersion", envelope.path("modelVersion").asText(""));
                        } catch (Exception ignored) { /* Malformed body is retained verbatim. */ }
                    } catch (Exception ex) {
                        event.put("status", 503); event.put("transportError", ex.getClass().getSimpleName());
                        event.put("body", "{}");
                    }
                    event.put("upstreamMs", (System.nanoTime()-start)/1_000_000.0);
                    event.put("finishedAt", Instant.now().toString()); event.put("completed", true);
                    atomic(target, event); // A received response survives a crash before the turn commit.
                }
                var observed = event.deepCopy(); observed.put("replayedFromDisk", replay); current.add(observed);
                if (!event.path("retryAfter").asText().isBlank())
                    exchange.getResponseHeaders().set("Retry-After", event.path("retryAfter").asText());
                byte[] bytes = event.path("body").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(event.path("status").asInt(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (Exception ex) {
                failure = ex.getMessage();
                byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(409, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            }
        });
        server.start();
    }

    URI root() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"); }
    void begin(String key) { turnKey = key; failure = null; current.clear(); }
    void check() { if (failure != null) throw new IllegalStateException(failure); }
    void prepare() throws Exception { awaitBudget(); }

    List<JsonNode> attempts(String key) throws Exception {
        try (var paths = Files.list(folder)) {
            var result = new ArrayList<JsonNode>();
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith(key + "-"))
                    .filter(p -> p.toString().endsWith(".json")).sorted().toList())
                result.add(JSON.readTree(path.toFile()));
            return result;
        }
    }

    List<JsonNode> ledger() throws Exception {
        var events = new ArrayList<JsonNode>(prior);
        try (var files = Files.list(folder)) {
            for (var path : files.filter(p -> p.toString().endsWith(".json")).toList()) events.add(JSON.readTree(path.toFile()));
        }
        return events;
    }

    void awaitBudget() throws Exception {
        while (true) {
            long wait = waitRequired(ledger(), Instant.now().toEpochMilli());
            if (wait == 0) return;
            Thread.sleep(Math.min(wait, 1000));
        }
    }

    static long waitRequired(List<JsonNode> events, long now) {
        // Run-wide accounting deliberately overcounts old/different-model smoke calls; never resets on resume.
        if (events.size() >= RPD) throw new IllegalStateException("Local daily request budget exhausted");
        long last = 0, oldest = Long.MAX_VALUE; int requests = 0, tokens = 0;
        for (var event : events) {
            long at = Instant.parse(event.path("startedAt").asText()).toEpochMilli(); last = Math.max(last, at);
            if (at > now-60_000) {
                requests++; oldest = Math.min(oldest, at);
                tokens += event.path("usageMetadata").has("promptTokenCount")
                    ? event.path("usageMetadata").path("promptTokenCount").asInt() : UNKNOWN_INPUT_TOKENS;
            }
        }
        long wait = Math.max(0, last + SPACING_MS-now);
        if (requests >= RPM || tokens + UNKNOWN_INPUT_TOKENS > TPM)
            wait = Math.max(wait, oldest + 60_001-now);
        return wait;
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            var result=JSON.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            for(String name:names)result.set(name,canonical(node.get(name)));return result;
        }
        if(node.isArray()) {var result=JSON.createArrayNode();node.forEach(v->result.add(canonical(v)));return result;}
        return node;
    }
    static void atomic(Path target, JsonNode value) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName()+".tmp");
        Files.writeString(temp, JSON.writeValueAsString(value));
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    @Override public void close() { server.stop(0); }
}
