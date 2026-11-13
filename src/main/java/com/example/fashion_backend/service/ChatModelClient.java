package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Transport contract shared by chat model providers. Parsing and grounding stay in Java. */
public interface ChatModelClient {
    Completion complete(String system, String input, JsonNode schema, int tokens, long remainingMs);

    Completion complete(String system, String input, JsonNode schema, int tokens);

    record Completion(String content, JsonNode usage, JsonNode timings, long elapsedMs) {}

    class Unavailable extends RuntimeException {
        public Unavailable(String code) { super(code); }

        public String reasonCode() {
            return "timeout".equals(getMessage()) ? "model_timeout" : getMessage();
        }
    }
}
