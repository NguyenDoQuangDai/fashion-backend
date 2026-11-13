package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatCriteriaParser parser;
    private final ChatRuleFallback rules;
    private final ChatProductSearchService search;
    private final ChatReplyService replies;
    private final String mode;
    private final long budgetMs;
    public ChatService(ChatCriteriaParser parser, ChatRuleFallback rules, ChatProductSearchService search,
            ChatReplyService replies, @Value("${app.chat.mode:rules}") String mode,
            @Value("${app.chat.timeout-seconds:60}") int seconds) {
        if (!Set.of("rules", "qwen", "gemini").contains(mode)) throw new IllegalArgumentException("Invalid chat mode");
        this.parser = parser; this.rules = rules; this.search = search; this.replies = replies; this.mode = mode;
        this.budgetMs = seconds * 1000L;
    }
    public ChatResponse chat(ChatRequest request) {
        request.validateInput();
        long start = System.nanoTime(); String requestId = UUID.randomUUID().toString();
        ChatCriteria c; String fallback = "none";
        try {
            if (!mode.equals("rules")) c = parser.parse(request).criteria();
            else { c = rules.parse(request); fallback = "rules_mode"; }
        } catch (ChatModelClient.Unavailable ex) {
            fallback = ex.reasonCode();
            // Malformed or ungrounded extraction must not become a broader rule search.
            c = Set.of("schema_error", "validation_error", "grounding_error", "blocked", "request_too_large",
                    "response_too_large", "invalid_content").contains(fallback) ? null : rules.parse(request);
        }
        if (c == null) {
            log.info("chat id={} mode={} fallback={} elapsedMs={}", requestId, mode, fallback, elapsed(start));
            return response("Tôi chưa phân tích được đầy đủ yêu cầu lúc này. Bạn có thể cho biết loại sản phẩm, ngân sách và màu muốn tìm không?", List.of());
        }
        if (c.needsClarification() || c.intent().equals("clarification")) {
            log.info("chat id={} mode={} fallback={} reason=clarification elapsedMs={}", requestId, mode, fallback, elapsed(start));
            return response("Bạn muốn tìm loại sản phẩm nào, với ngân sách, màu hoặc size như thế nào?", List.of());
        }
        if (c.intent().equals("greeting")) return response("Xin chào! Bạn muốn tìm sản phẩm nào? Có thể cho tôi biết loại, ngân sách hoặc màu bạn thích.", List.of());
        if (c.intent().equals("unsupported_store_request"))
            return response("Chatbot hiện hỗ trợ tìm sản phẩm; chưa tra cứu đơn hàng, thanh toán hoặc xác nhận chính sách đổi trả. Bạn có thể liên hệ cửa hàng để được hỗ trợ.", List.of());
        if (c.intent().equals("out_of_scope")) return response("Tôi hỗ trợ tư vấn tìm sản phẩm thời trang. Bạn muốn tham khảo sản phẩm nào?", List.of());
        List<ChatProductSearchService.Match> matched;
        try { matched = search.search(c); }
        catch (RuntimeException ex) {
            log.warn("chat id={} catalog_unavailable", requestId);
            return response("Tạm thời tôi chưa tra cứu được sản phẩm. Vui lòng thử lại sau.", List.of());
        }
        long remaining = !mode.equals("rules") && fallback.equals("none") ? budgetMs - elapsed(start) : 0;
        String reply = replies.reply(c, matched, remaining);
        log.info("chat id={} mode={} fallback={} matched={} elapsedMs={}", requestId, mode, fallback, matched.size(), elapsed(start));
        return response(reply, matched.stream().map(ChatProductSearchService.Match::product).toList());
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private ChatResponse response(String reply, List<ChatSuggestedProduct> products) {
        var response = new ChatResponse(); response.setReply(reply); response.setSuggestedProducts(products); return response;
    }
}
