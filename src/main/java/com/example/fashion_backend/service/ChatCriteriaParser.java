package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ChatCriteriaParser {
    private final ChatModelClient client;
    private final ObjectMapper json = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JsonNode schema;
    private final String prompt;

    public ChatCriteriaParser(ChatModelClient client) {
        this.client = client;
        try {
            schema = json.readTree(getClass().getResourceAsStream("/chat/criteria.schema.json"));
            try (var stream = getClass().getResourceAsStream("/chat/extraction.txt")) {
                prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ex) { throw new IllegalStateException("Missing chat resources", ex); }
    }

    public record Parsed(ChatCriteria criteria, ChatModelClient.Completion completion, JsonNode rawCriteria, String reasonCode) {
        public Parsed(ChatCriteria criteria, ChatModelClient.Completion completion) {
            this(criteria, completion, null, criteria.needsClarification() ? "clarification" : "ok");
        }
    }
    public static class CriteriaFailure extends IllegalArgumentException {
        private final String reasonCode;
        CriteriaFailure(String reasonCode) { super(reasonCode); this.reasonCode = reasonCode; }
        public String reasonCode() { return reasonCode; }
    }
    public Parsed parse(ChatRequest request) {
        request.validateInput();
        try {
            // User history is data, never promoted to instructions/system messages.
            // Map.of has JVM-randomized iteration order. Prompt bytes must be stable for replay.
            var data = new LinkedHashMap<String,Object>();
            data.put("history", request.getHistory()); data.put("message", request.getMessage());
            var input = json.writeValueAsString(data);
            var result = client.complete(prompt + "\nJSON schema:\n" + schema.toString(), input, schema, 512);
            JsonNode raw;
            try { raw = json.readTree(result.content()); }
            catch (IOException ex) { throw new CriteriaFailure("schema_error"); }
            var criteria = validate(raw);
            if (!criteria.needsClarification()) {
                try { criteria = ChatPricePolicy.apply(request, criteria); }
                catch (IllegalArgumentException ex) { throw new CriteriaFailure("validation_error"); }
                try { criteria = ChatCriteriaGrounding.validate(request, criteria); }
                catch (IllegalArgumentException ex) { throw new CriteriaFailure("grounding_error"); }
            }
            return new Parsed(criteria, result, raw.deepCopy(), criteria.needsClarification() ? "clarification" : "ok");
        } catch (ChatModelClient.Unavailable ex) { throw ex;
        } catch (CriteriaFailure ex) { throw new ChatModelClient.Unavailable(ex.reasonCode());
        } catch (IOException ex) { throw new ChatModelClient.Unavailable("serialization_error"); }
    }

    public ChatCriteria validate(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != schema.path("required").size()) throw invalid();
        var properties = schema.path("properties");
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next(); JsonNode value = node.get(name), rule = properties.path(name);
            if (rule.isMissingNode()) throw invalid();
            boolean typeOk = false;
            for (JsonNode type : rule.path("type").isArray() ? rule.path("type") : List.of(rule.path("type"))) {
                typeOk |= switch (type.asText()) {
                    case "null" -> value.isNull(); case "string" -> value.isTextual();
                    case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
                    case "boolean" -> value.isBoolean(); case "array" -> value.isArray(); default -> false;
                };
            }
            if (!typeOk || (rule.has("enum") && !contains(rule.get("enum"), value))) throw invalid();
            if (value.isTextual() && value.asText().length() > rule.path("maxLength").asInt(200)) throw invalid();
            if (value.isIntegralNumber() && (value.longValue() < rule.path("minimum").asLong(0)
                    || value.longValue() > rule.path("maximum").asLong(1_000_000_000L))) throw invalid();
            if (value.isArray()) {
                if (value.size() > 5) throw invalid();
                var seen = new HashSet<String>();
                for (var item : value) {
                    if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 40
                            || !seen.add(ChatText.normalize(item.asText()))) throw invalid();
                }
            }
        }
        try {
            var criteria = json.treeToValue(node, ChatCriteria.class);
            boolean conflict = criteria.priceMin() != null && criteria.priceMax() != null &&
                    (criteria.priceMin() > criteria.priceMax() || (criteria.priceMin().equals(criteria.priceMax())
                    && (!criteria.priceMinInclusive() || !criteria.priceMaxInclusive())));
            for (String color : criteria.colorsInclude())
                conflict |= criteria.colorsExclude().stream().anyMatch(c -> ChatText.contains(color, c) || ChatText.contains(c, color));
            boolean clarify = criteria.needsClarification() || "clarification".equals(criteria.intent()) || conflict;
            if ("product_refinement".equals(criteria.intent()) || "product_search".equals(criteria.intent())) {
                if (criteria.category() == null && criteria.productType() == null && criteria.gender() == null
                        && criteria.occasion() == null && criteria.colorsInclude().isEmpty() && criteria.priceMax() == null
                        && criteria.priceMin() == null && criteria.size() == null && !clarify) throw new CriteriaFailure("validation_error");
            }
            return new ChatCriteria(clarify ? "clarification" : criteria.intent(), criteria.category(), clean(criteria.productType()), criteria.gender(),
                    criteria.priceMin(), criteria.priceMax(), criteria.priceMinInclusive(), criteria.priceMaxInclusive(),
                    criteria.colorsInclude().stream().map(this::clean).toList(), criteria.colorsExclude().stream().map(this::clean).toList(),
                    criteria.size() == null ? null : criteria.size().trim().toUpperCase(Locale.ROOT),
                    criteria.materialPreferences().stream().map(this::clean).toList(), criteria.occasion(), criteria.quantity(),
                    criteria.sort(), clarify, clarify ? (criteria.clarificationQuestion() == null || criteria.clarificationQuestion().isBlank()
                        ? "Bạn muốn làm rõ loại sản phẩm, ngân sách, màu hoặc size nào?" : criteria.clarificationQuestion()) : null);
        } catch (IOException ex) { throw invalid(); }
    }
    private boolean contains(JsonNode values, JsonNode value) { for (var v : values) if (v.equals(value)) return true; return false; }
    private String clean(String value) { return value == null ? null : ChatText.normalize(value); }
    private IllegalArgumentException invalid() { return new CriteriaFailure("schema_error"); }
    public JsonNode schema() { return schema.deepCopy(); }
}
