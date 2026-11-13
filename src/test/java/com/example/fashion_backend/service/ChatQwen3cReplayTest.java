package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatRequest;
import com.example.fashion_backend.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Replays frozen dev raw outputs through the shared Java pipeline; never calls a model. */
class ChatQwen3cReplayTest {
    @Test void frozenDevRawOutputsKeepTheSameCriteriaReasonsAndCards() throws Exception {
        Path folder = Path.of("../reports/chatbot/qwen3-v1");
        Path results = folder.resolve("results-dev-2b2-r3c-b2.jsonl");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(results)
            && Files.isRegularFile(folder.resolve("catalog.json")));
        var json = new ObjectMapper();
        List<Product> catalog = List.of(json.readValue(folder.resolve("catalog.json").toFile(), Product[].class));
        var search = new ChatProductSearchService(null);
        int replayed = 0;
        for (String line : Files.readAllLines(results)) {
            var row = json.readTree(line);
            if (row.path("excludedFromNlu").asBoolean()) continue;
            assertEquals(1, row.path("inference").size(), row.path("id").asText());
            var client = mock(ChatModelClient.class);
            var recorded = row.path("inference").get(0);
            when(client.complete(anyString(), anyString(), any(), eq(512))).thenReturn(
                new ChatModelClient.Completion(recorded.path("content").asText(),
                    recorded.path("usage"), recorded.path("timings"), recorded.path("elapsedMs").asLong()));
            var request = json.treeToValue(row.path("input"), ChatRequest.class);
            var parsed = new ChatCriteriaParser(client).parse(request);
            String at = row.path("id").asText() + " turn=" + row.path("turn").asInt();
            assertTrue(sameJson(row.path("rawCriteria"), parsed.rawCriteria()), at + " raw");
            assertTrue(sameJson(row.path("actual"), json.valueToTree(parsed.criteria())), at + " post-Java");
            assertEquals(row.path("reasonCode").asText(), parsed.reasonCode(), at + " reason");
            var matches = row.path("retrievalExecuted").asBoolean()
                ? search.search(parsed.criteria(), catalog) : List.<ChatProductSearchService.Match>of();
            var expectedCards = row.path("products");
            var actualCards = json.valueToTree(matches.stream().map(ChatProductSearchService.Match::product).toList());
            assertEquals(expectedCards.size(), actualCards.size(), at + " card count");
            for (int i = 0; i < expectedCards.size(); i++) {
                var expectedCard = expectedCards.get(i);
                var actualCard = actualCards.get(i);
                for (String field : List.of("id", "title", "slug", "coverImage", "matchedColor", "matchedSize"))
                    assertEquals(expectedCard.path(field), actualCard.path(field), at + " card " + i + " " + field);
                assertEquals(0, expectedCard.path("price").decimalValue()
                    .compareTo(actualCard.path("price").decimalValue()), at + " card " + i + " price");
            }
            replayed++;
        }
        assertEquals(46, replayed, "The frozen 3C dev run has 46 model turns");
    }

    private boolean sameJson(JsonNode expected, JsonNode actual) {
        return expected.equals((left, right) -> {
            if (left.isNumber() && right.isNumber())
                return left.decimalValue().compareTo(right.decimalValue());
            return left.equals(right) ? 0 : 1;
        }, actual);
    }
}
