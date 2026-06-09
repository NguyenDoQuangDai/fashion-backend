package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatRequest;
import com.example.fashion_backend.dto.chat.ChatResponse;
import com.example.fashion_backend.dto.chat.ChatSuggestedProduct;
import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.model.ProductColorVariant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final int MAX_PRODUCTS = 5;
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d{2,7})(\\s?)(k|ngan)?");

    private final ProductService productService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public ChatService(ProductService productService,
                       ObjectMapper objectMapper,
                       @Value("${app.gemini.api-key:}") String apiKey,
                       @Value("${app.gemini.model:gemini-3.5-flash}") String model) {
        this.productService = productService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public ChatResponse chat(ChatRequest request) {
        String message = request.getMessage();
        ChatResponse response = new ChatResponse();

        log.debug("Chat request received: {}", message);

        if (!isAllowed(message)) {
            log.debug("Chat request blocked by guardrails");
            response.setReply("Xin lỗi, tôi chỉ hỗ trợ câu hỏi liên quan sản phẩm, đơn hàng hoặc cửa hàng.");
            response.setSuggestedProducts(List.of());
            return response;
        }

        String normalized = normalizeMessage(message).toLowerCase(Locale.ROOT);
        String keyword = extractKeyword(normalized);
        String gender = extractGender(normalized);
        BigDecimal priceTarget = extractPrice(normalized);
        BigDecimal[] priceRange = buildPriceRange(priceTarget);

        // Trích xuất số lượng yêu cầu và loại sản phẩm (áo/quần)
        int limit = extractQuantity(normalized);
        String catType = extractCategoryType(normalized);

        // Truyền thêm limit và catType vào hàm tìm sản phẩm
        List<Product> matched = findProducts(keyword, gender, priceRange[0], priceRange[1], limit, catType);
        response.setSuggestedProducts(mapSuggestedProducts(matched));

        log.debug("Chat matched {} products", matched.size());

        String prompt = buildPrompt(message, matched, priceTarget, keyword, gender);
        String reply = callGemini(prompt);
        response.setReply(reply);
        return response;
    }

    private boolean isAllowed(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = normalizeMessage(message).toLowerCase(Locale.ROOT);
        String[] keywords = new String[] {
                "nam", "nu", "unisex", "san pham", "hang", "size", "gia", "mau", "mau sac", "chat lieu",
                "don hang", "dat hang", "giao hang", "doi tra", "bao hanh", "cua hang", "shop",
                "ao", "quan", "thun", "polo", "ba lo", "chong nang", "ni", "len", "khoac", "phao", "quan short",
                "slimfit", "casual", "basic", "the thao", "quan dai kaki", "set bo", "bo do", "chun", "co tron",
                "co khoa", "can doi", "tam giac", "boxer", "brief", "sip", "jogger", "giu nhiet", "au", "vay",
                "kaki", "jeans", "tay", "chan vay"
        };
        for (String keyword : keywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private String buildPrompt(String message, List<Product> matched, BigDecimal priceTarget, String keyword, String gender) {
        String productBlock = matched.isEmpty() ? "Khong co san pham phu hop." : matched.stream()
                .map(p -> String.format("- %s | id=%s | gia=%s | tags=%s",
                        safe(p.getTitle()), safe(p.getId()),
                        p.getPrice() == null ? "n/a" : p.getPrice().toString(),
                        p.getTags() == null ? "" : String.join(",", p.getTags())))
                .collect(Collectors.joining("\n"));

        String priceHint = priceTarget == null ? "" : "\nYeu cau gia khoang: " + priceTarget.toString();
        String keywordHint = keyword == null ? "" : "\nTu khoa san pham: " + keyword;
        String genderHint = gender == null ? "" : "\nGioi tinh: " + gender;

        return "Ban la tro ly cua cua hang thoi trang. Ban CHI duoc tu van dua tren thong tin cac san pham duoc cung cap.\n\n"
                + "I. QUY DINH DINH DANG DANH SACH (BAT BUOC):\n"
                + "- KHONG DUOC viet lien cac san pham tren cung mot dong.\n"
                + "- BAT BUOC phai xuong dong sau moi ten san pham va sau moi thong tin gia.\n"
                + "- TUYET DOI KHONG SU DUNG dau sao doi `**` de boi dam chu. Hay ghi van ban thuong, boi dam se lam loi giao dien.\n"
                + "- Phai su dung cau truc danh sach xuong dong ro rang chu so chan phuong nhu vi du sau:\n"
                + "1. Ten san pham A\n"
                + "- Gia: 100.000đ\n"
                + "2. Ten san pham B\n"
                + "- Gia: 200.000đ\n\n"
                
                + "II. GIOI HAN CHUC NANG CHATBOT (NGHIEM CAM VI PHAM):\n"
                + "- TUYET DOI KHONG su dung cac tu: 'size', 'kich co', 'dat hang', 'don hang', 'thanh toan' trong cau tra loi.\n"
                + "- KHONG GOI Y, KHONG MOI CHAO nguoi dung check size hoac dat hang vi he thong chua co API nay.\n"
                + "- Luon ket thuc bang mot cau hoi huong nguoi dung tim kiem san pham khac (Vi du: 'Anh/Chi co muon tham khao them san pham nao khac khong?').\n\n"
                
                + "DỮ LIỆU ĐẦU VÀO:\n"
                + "Nguoi dung hoi: " + message + "\n"
                + priceHint + keywordHint + genderHint + "\n"
                + "Danh sach san pham phu hop trong kho:\n" + productBlock + "\n\n"
                
                + "YEU CAU TRA LOI:\n"
                + "Hay lam tro ly lich su, tra loi bang Tieng Viet. Tuan thu tuyet doi quy dinh xuong dong o muc (I) va lenh cam o muc (II).";
    }

    private String callGemini(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY is missing");
            return "Hệ thống chưa cấu hình GEMINI_API_KEY.";
        }
        try {
            String endpoint = String.format(GEMINI_ENDPOINT, model, apiKey);
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            );
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Gemini response error: {}", response.statusCode());
                return "Hệ thống đã xử lý hoàn tất nhưng không thể kết nối với AI chatbot.";
            }
            return extractReply(response.body());
        } catch (Exception ex) {
            log.warn("Gemini call failed", ex);
            return "Hệ thống đã xử lý hoàn tất nhưng không thể kết nối AI chatbot.";
        }
    }

    private String extractReply(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode text = candidates.get(0).path("content").path("parts");
                if (text.isArray() && text.size() > 0) {
                    return text.get(0).path("text").asText("");
                }
            }
        } catch (Exception ignored) {
        }
        return "Xin lỗi, hiện tại tôi không thể trả lời.";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalizeMessage(String message) {
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFD);
        normalized = DIACRITICS.matcher(normalized).replaceAll("");
        normalized = normalized.replace('đ', 'd').replace('Đ', 'D');
        return normalized;
    }

    private List<Product> findProducts(String keyword, String gender, BigDecimal priceMin, BigDecimal priceMax, int limit, String catType) {
        String query = keyword == null ? "" : keyword;
        List<Product> results = productService.search(query, priceMin, priceMax, gender, null, null, null);
        
        // Lọc theo loại sản phẩm (áo/quần)
        results = filterByCat(results, catType);

        if (results.isEmpty() && (priceMin != null || priceMax != null)) {
            results = productService.search(query, null, null, gender, null, null, null);
            results = filterByCat(results, catType);
        }
        
        // Nếu tìm theo từ khóa bị rỗng, ép tìm kiếm theo danh mục chính (ao/quan)
        if (results.isEmpty() && catType != null) {
            results = productService.search(catType, priceMin, priceMax, gender, null, null, null);
            if (results.isEmpty() && (priceMin != null || priceMax != null)) {
                results = productService.search(catType, null, null, gender, null, null, null);
            }
            results = filterByCat(results, catType);
        }

        if (results.isEmpty() && keyword == null) {
            results = productService.suggested(limit);
            results = filterByCat(results, catType);
        }
        
        // Giới hạn đúng số lượng khách yêu cầu
        return results.stream().limit(limit).collect(Collectors.toList());
    }

    private String extractGender(String normalized) {
        if (normalized.contains("nam")) return "nam";
        if (normalized.contains("nu")) return "nu";
        return null;
    }

    private String extractKeyword(String normalized) {
        String[] keywords = new String[] {
                "chong nang",
                "giu nhiet",
                "chan vay",
                "quan short",
                "quan dai",
                "quan tay",
                "quan au",
                "quan sip",
                "phu kien",
                "jeans",
                "polo",
                "thun",
                "phao",
                "khoac",
                "len",
                "ni",
                "ba lo",
                "kaki",
                "casual",
                "the thao",
                "sip",
                "vay",
                "quan",
                "ao"
        };

        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    // Hàm lấy số lượng sản phẩm khách yêu cầu
    private int extractQuantity(String normalized) {
        Pattern pattern = Pattern.compile("(\\d+)\\s*(mau|cai|san pham|sp|item|chiec)");
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            try {
                int qty = Integer.parseInt(matcher.group(1));
                if (qty > 0 && qty <= 10) return qty; // Giới hạn hợp lý tránh lỗi bộ nhớ
            } catch (NumberFormatException ignored) {}
        }
        return MAX_PRODUCTS; // Mặc định nếu không phân tích được số lượng (3)
    }

    // Hàm nhận diện khách đang hỏi đích danh Áo, Quần hay Váy
    private String extractCategoryType(String normalized) {
        if (normalized.contains("ao")) return "ao";
        if (normalized.contains("quan")) return "quan";
        if (normalized.contains("vay") || normalized.contains("dam")) return "vay";
        return null;
    }

    // Hàm lọc danh sách sản phẩm nghiêm ngặt theo loại danh mục đã nhận diện
    private List<Product> filterByCat(List<Product> products, String catType) {
        if (catType == null) return products;
        return products.stream()
                .filter(p -> {
                    String title = normalizeMessage(p.getTitle()).toLowerCase(Locale.ROOT);
                    if ("ao".equals(catType)) {
                        return title.contains("ao") && !title.contains("quan");
                    }
                    if ("quan".equals(catType)) {
                        return title.contains("quan") && !title.contains("ao");
                    }
                    if ("vay".equals(catType)) {
                        return title.contains("vay") || title.contains("dam");
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private BigDecimal extractPrice(String normalized) {
        Matcher matcher = PRICE_PATTERN.matcher(normalized);
        if (!matcher.find()) return null;
        long value = Long.parseLong(matcher.group(1));
        String suffix = matcher.group(3);
        if (suffix != null && !suffix.isBlank()) {
            value *= 1000;
        }
        if (value < 1000 && normalized.contains("k")) {
            value *= 1000;
        }
        return BigDecimal.valueOf(value);
    }

    private BigDecimal[] buildPriceRange(BigDecimal priceTarget) {
        if (priceTarget == null) return new BigDecimal[] {null, null};
        long target = priceTarget.longValue();
        long min = Math.max(0, target - 50000);
        long max = target + 50000;
        return new BigDecimal[] {BigDecimal.valueOf(min), BigDecimal.valueOf(max)};
    }

    private List<ChatSuggestedProduct> mapSuggestedProducts(List<Product> products) {
        return products.stream()
                .map(this::toSuggestedProduct)
                .collect(Collectors.toList());
    }

    private ChatSuggestedProduct toSuggestedProduct(Product product) {
        ChatSuggestedProduct dto = new ChatSuggestedProduct();
        dto.setId(product.getId());
        dto.setTitle(product.getTitle());
        dto.setSlug(product.getSlug());
        dto.setPrice(product.getPrice());
        dto.setCoverImage(resolveCoverImage(product));
        return dto;
    }

    private String resolveCoverImage(Product product) {
        if (product == null) return null;
        String coverImage = product.getCoverImage();
        if (!isBlank(coverImage)) return coverImage;

        List<ProductColorVariant> colors = product.getColors();
        if (colors != null) {
            for (ProductColorVariant color : colors) {
                if (color == null) continue;
                if (!isBlank(color.getCoverImage())) return color.getCoverImage();
                List<String> images = color.getImages();
                if (images != null && !images.isEmpty() && !isBlank(images.get(0))) {
                    return images.get(0);
                }
            }
        }

        List<String> generalImages = product.getGeneralImages();
        if (generalImages != null && !generalImages.isEmpty() && !isBlank(generalImages.get(0))) {
            return generalImages.get(0);
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
