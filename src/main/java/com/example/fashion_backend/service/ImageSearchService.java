package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.search.ImageSearchDtos.*;
import com.example.fashion_backend.repository.ProductRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageSearchService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final ModaClient moda;
    private final ProductRepository products;
    private final ProductService productService;
    private final ImageMappingResolver mapping;
    private final double minSimilarity;
    private final int maxResults;
    public ImageSearchService(ModaClient moda, ProductRepository products, ProductService productService, ImageMappingResolver mapping,
                              @Value("${app.image-search.min-similarity:0.55}") double minSimilarity,
                              @Value("${app.image-search.max-results:24}") int maxResults) {
        if (!Double.isFinite(minSimilarity) || minSimilarity < -1 || minSimilarity > 1 || maxResults < 1)
            throw new IllegalArgumentException("Invalid image search similarity threshold or result limit");
        this.moda = moda; this.products = products; this.productService = productService; this.mapping = mapping;
        this.minSimilarity = minSimilarity; this.maxResults = maxResults;
    }

    @Transactional(readOnly = true)
    public Response search(MultipartFile file, String requestId) {
        byte[] bytes = validate(file);
        AiResponse ranked = moda.search(bytes, file.getContentType(), requestId);
        validateRanking(ranked);
        var candidates = ranked.items().stream().filter(item -> item.similarity() >= minSimilarity).toList();
        if (candidates.isEmpty())
            return new Response(requestId, "moda", ranked.indexVersion(), ranked.indexedProductCount(), 0, java.util.List.of());
        var byId = products.findAllById(candidates.stream().map(AiItem::productId).toList()).stream()
                .collect(Collectors.toMap(p -> p.getId(), Function.identity()));
        var result = new ArrayList<Item>();
        for (var item : candidates) {
            var product = byId.get(item.productId());
            // Same visibility policy as the existing catalog: existing DB rows, including sold-out items.
            if (product == null) continue;
            result.add(new Item(productService.mapToDto(product), item.similarity(), item.rank(),
                    mapping.resolve(product, item.matchedImage(), requestId, ranked.indexVersion())));
            if (result.size() >= maxResults) break;
        }
        return new Response(requestId, "moda", ranked.indexVersion(), ranked.indexedProductCount(), result.size(), result);
    }

    static void validateRanking(AiResponse ranked) {
        if (ranked == null || !"moda".equals(ranked.model()) || ranked.indexVersion() == null
                || ranked.indexVersion().isBlank() || ranked.items() == null
                || ranked.indexedProductCount() != ranked.items().size()) invalidResponse();
        Set<String> ids = new HashSet<>();
        double previous = Double.POSITIVE_INFINITY;
        int rank = 1;
        for (var item : ranked.items()) {
            if (item == null || item.productId() == null || item.productId().isBlank() || !ids.add(item.productId())
                    || !Double.isFinite(item.similarity()) || item.similarity() < -1 || item.similarity() > 1
                    || item.similarity() > previous || item.rank() != rank++) invalidResponse();
            previous = item.similarity();
        }
    }

    private static void invalidResponse() {
        throw new ImageSearchException(502, "AI_INVALID_RESPONSE", "Dịch vụ tìm ảnh trả về thứ hạng không hợp lệ.");
    }

    static byte[] validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ImageSearchException(400, "INVALID_IMAGE", "Vui lòng chọn một ảnh.");
        if (file.getSize() > MAX_BYTES) throw new ImageSearchException(413, "IMAGE_TOO_LARGE", "Ảnh vượt quá 5 MB.");
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(file.getContentType() == null ? "" : file.getContentType()))
            throw new ImageSearchException(415, "UNSUPPORTED_IMAGE", "Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.");
        try {
            byte[] data = file.getBytes();
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("Unsupported image");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    String mime = switch (format) { case "jpeg", "jpg" -> "image/jpeg"; case "png" -> "image/png"; case "webp" -> "image/webp"; default -> ""; };
                    if (!mime.equals(file.getContentType()) || (long) reader.getWidth(0) * reader.getHeight(0) > 25_000_000)
                        throw new IOException("Invalid image dimensions or signature");
                    reader.read(0);
                } finally { reader.dispose(); }
            }
            return data;
        } catch (IOException | RuntimeException ex) {
            throw new ImageSearchException(400, "INVALID_IMAGE", "Ảnh bị lỗi hoặc vượt quá 25 megapixel. Vui lòng chọn ảnh khác.");
        }
    }
}
