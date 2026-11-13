package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Deliberately narrow grammar: unknown constraints ask again instead of dropping them. */
@Component
public class ChatRuleFallback {
    private static final Pattern SIMPLE = Pattern.compile("(?:tim |cho |goi y )?(ao|quan|vay)(?: (polo|thun|khoac|kaki|jeans|short))?(?: (nam|nu|unisex))?");
    public ChatCriteria parse(ChatRequest request) {
        if (!request.getHistory().isEmpty()) return null;
        var match = SIMPLE.matcher(ChatText.normalize(request.getMessage()));
        if (!match.matches()) return null;
        return new ChatCriteria("product_search", match.group(1), match.group(2), match.group(3),
                null, null, true, true, List.of(), List.of(), null, List.of(), null, 5, "relevance", false, null);
    }
}
