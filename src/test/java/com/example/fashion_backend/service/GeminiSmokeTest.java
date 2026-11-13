package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Manual, opt-in six-call dev smoke; no retries, no holdout, no key in artifacts. */
@EnabledIfSystemProperty(named = "chat.gemini.smoke", matches = "true")
class GeminiSmokeTest {
    @Test void sixFrozenSingleTurnDevCases() throws Exception {
        var environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String key = environment.getProperty("app.chat.gemini.api-key");
        String model = environment.getProperty("app.chat.gemini.model");
        assertNotNull(key, "Backend Gemini key is not configured");
        assertFalse(key.isBlank(), "Backend Gemini key is not configured");
        assertEquals("gemini-3.5-flash-lite", model);

        var client = new GeminiChatClient(URI.create("https://generativelanguage.googleapis.com/v1beta"),
            key, model, 60, 0);
        var parser = new ChatCriteriaParser(client);
        var json = new ObjectMapper();
        Path folder = Path.of("../reports/chatbot/qwen3-v1");
        Path output = Path.of(System.getProperty("chat.gemini.smoke.output",
            "../reports/chatbot/gemini-checkpoint-b-smoke-3.5-flash-lite.jsonl"));
        assertFalse(Files.exists(output), "Preserve the previous smoke artifact; use a new output name");
        Set<String> ids = Set.of("basic-01", "budget-01", "constraints-02", "constraints-05", "language-03", "support-01");
        int calls = 0;
        for (String line : Files.readAllLines(folder.resolve("cases.jsonl"))) {
            var row = json.readTree(line);
            if (!"dev".equals(row.path("split").asText()) || !ids.contains(row.path("id").asText())) continue;
            assertEquals(1, row.path("turns").size(), "Smoke cases must stay single-turn");
            assertTrue(calls < 6, "Hard call cap");
            if (calls > 0) Thread.sleep(5_000); // 15 RPM project quota, with a margin.
            var request = new ChatRequest();
            request.setMessage(row.path("turns").get(0).asText());
            calls++;
            var recorded = json.createObjectNode();
            recorded.put("id", row.path("id").asText());
            recorded.put("model", model);
            recorded.put("time", Instant.now().toString());
            boolean failed = false;
            try {
                var parsed = parser.parse(request);
                recorded.put("reasonCode", parsed.reasonCode());
                recorded.set("rawCriteria", parsed.rawCriteria());
                recorded.set("actual", json.valueToTree(parsed.criteria()));
                recorded.set("usageMetadata", parsed.completion().usage());
                recorded.put("elapsedMs", parsed.completion().elapsedMs());
                recorded.put("modelVersion", client.lastModelVersion());
            } catch (ChatModelClient.Unavailable ex) {
                recorded.put("reasonCode", ex.reasonCode());
                failed = true;
            }
            Files.writeString(output, json.writeValueAsString(recorded) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (failed) break;
        }
        assertEquals(6, calls);
    }
}
