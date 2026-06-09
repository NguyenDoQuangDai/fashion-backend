package com.example.fashion_backend.controller;

import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.service.RecommendationService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping
    public List<Product> recommend(
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "priceMin", required = false) BigDecimal priceMin,
            @RequestParam(value = "priceMax", required = false) BigDecimal priceMax,
            @RequestParam(value = "historyIds", required = false) String historyIds) {
        List<String> ids = historyIds == null || historyIds.isBlank() ? List.of() :
                Arrays.stream(historyIds.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
        return recommendationService.recommend(size, gender, tag, priceMin, priceMax, ids);
    }
}
