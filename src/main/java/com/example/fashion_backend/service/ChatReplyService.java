package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.NumberFormat;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The selected model chooses evidence codes; only the backend renders factual text. */
@Component
public class ChatReplyService {
    private final ChatModelClient client;
    private final boolean generate;
    private final ObjectMapper json = new ObjectMapper();
    public ChatReplyService(ChatModelClient client, @Value("${app.chat.generate-reply:false}") boolean generate) {
        this.client = client; this.generate = generate;
    }
    public String reply(ChatCriteria c, List<ChatProductSearchService.Match> matches, long remainingMs) {
        if (matches.isEmpty()) return "Hiện chưa tìm thấy sản phẩm còn hàng đáp ứng các điều kiện này. Bạn muốn thay đổi ngân sách, màu hoặc loại sản phẩm nào?";
        var selected = new HashMap<String, String>();
        if (generate && remainingMs > 0) {
            try {
                var candidates = matches.stream().map(m -> {
                    var candidate = new LinkedHashMap<String,Object>();
                    candidate.put("id",m.product().getId()); candidate.put("allowedReasons",m.evidence()); return candidate;
                }).toList();
                var schema = json.readTree("""
                    {"type":"object","properties":{"reasons":{"type":"array","maxItems":10,"items":{
                    "type":"object","properties":{"id":{"type":"string"},"reason":{"type":"string","enum":["budget","color","size","material","occasion"]}},
                    "required":["id","reason"],"additionalProperties":false}}},"required":["reasons"],"additionalProperties":false}
                    """);
                var output = client.complete("Chọn tối đa một lý do trong allowedReasons cho mỗi id. Không có lý do thì bỏ qua. Trả JSON, không thêm dữ kiện.",
                        json.writeValueAsString(candidates), schema, 256, remainingMs);
                var root = json.readTree(output.content());
                if (root.size() != 1 || !root.path("reasons").isArray() || root.path("reasons").size() > matches.size()) throw new IllegalArgumentException();
                for (var item : root.path("reasons")) {
                    var match = matches.stream().filter(m -> m.product().getId().equals(item.path("id").asText())).findFirst().orElseThrow();
                    if (item.size() != 2 || !match.evidence().contains(item.path("reason").asText())
                            || selected.putIfAbsent(match.product().getId(), item.path("reason").asText()) != null) throw new IllegalArgumentException();
                }
            } catch (Exception ex) { selected.clear(); }
        }
        var result = new StringBuilder("Tôi tìm thấy " + matches.size() + " sản phẩm phù hợp, còn hàng:\n");
        var money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        for (int i = 0; i < matches.size(); i++) {
            var p = matches.get(i).product();
            result.append(i + 1).append(". ").append(p.getTitle()).append("\nGiá: ").append(money.format(p.getPrice())).append(" đ")
                    .append(" — ").append(p.getMatchedColor()).append(", size ").append(p.getMatchedSize());
            String reason = selected.get(p.getId());
            if (reason != null) result.append("\n").append(switch (reason) {
                case "budget" -> "Giá đáp ứng ngân sách bạn yêu cầu.";
                case "color" -> "Biến thể này đáp ứng điều kiện màu bạn yêu cầu.";
                case "size" -> "Size yêu cầu hiện còn hàng ở biến thể này.";
                case "material" -> "Mô tả có thông tin chất liệu bạn quan tâm.";
                default -> "Mô tả có nhắc mục đích sử dụng bạn quan tâm.";
            });
            result.append("\n");
        }
        if (!c.materialPreferences().isEmpty() || c.occasion() != null)
            result.append("Chất liệu và mục đích mặc chỉ được ưu tiên khi mô tả có thông tin; các đặc tính khác chưa được xác nhận.\n");
        return result.append("Bạn muốn điều chỉnh ngân sách, màu hoặc loại sản phẩm không?").toString();
    }
}
