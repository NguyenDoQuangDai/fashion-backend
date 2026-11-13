package com.example.fashion_backend.dto.search;

import com.example.fashion_backend.model.Product;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ImageSearchDtos {
    private ImageSearchDtos() {}
    public record AiImage(String imageKey, String sourceUrl, @JsonProperty("color_id") String colorId) {}
    public record AiItem(@JsonProperty("product_id") String productId, double similarity, int rank, AiImage matchedImage) {}
    public record AiResponse(String model, String indexVersion, int indexedProductCount, List<AiItem> items) {}
    public record MatchedImage(String imageKey, Long imageId, String url, String colorId) {}
    public record Item(Product product, double similarity, int rank, MatchedImage matchedImage) {}
    public record Response(String requestId, String model, String indexVersion, int indexedProductCount,
                           int returnedProductCount, List<Item> items) {}
}
