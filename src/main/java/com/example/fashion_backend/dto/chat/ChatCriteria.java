package com.example.fashion_backend.dto.chat;

import java.util.List;

/** Validated, complete search state for the current turn. Money is integer VND. */
public record ChatCriteria(String intent, String category, String productType, String gender,
        Long priceMin, Long priceMax, boolean priceMinInclusive, boolean priceMaxInclusive,
        List<String> colorsInclude, List<String> colorsExclude, String size,
        List<String> materialPreferences, String occasion, int quantity, String sort,
        boolean needsClarification, String clarificationQuestion) {
}
