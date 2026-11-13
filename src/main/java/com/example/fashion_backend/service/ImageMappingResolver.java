package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.search.ImageSearchDtos.AiImage;
import com.example.fashion_backend.dto.search.ImageSearchDtos.MatchedImage;
import com.example.fashion_backend.entity.ProductEntity;
import java.net.URI;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImageMappingResolver {
    private static final Logger log = LoggerFactory.getLogger(ImageMappingResolver.class);

    public MatchedImage resolve(ProductEntity product, AiImage image, String requestId, String indexVersion) {
        String reason = "IMAGE_NOT_OWNED";
        if (image != null && image.imageKey() != null && !image.imageKey().isBlank() && safeUrl(image.sourceUrl())) {
            String colorId = image.colorId();
            if (colorId != null && !colorId.isBlank()) {
                reason = "COLOR_NOT_FOUND";
                if (product.getColors() != null) for (var color : product.getColors()) {
                    if (!Objects.equals(colorId, color.getColorId())) continue;
                    reason = "IMAGE_NOT_OWNED";
                    if (color.getImages() != null) for (var candidate : color.getImages()) {
                        if (Objects.equals(image.sourceUrl(), candidate.getImageUrl()))
                            return new MatchedImage(image.imageKey(), candidate.getId(), candidate.getImageUrl(), colorId);
                    }
                    if (Objects.equals(image.sourceUrl(), color.getCoverImage()))
                        return new MatchedImage(image.imageKey(), null, color.getCoverImage(), colorId);
                }
            } else {
                if (Objects.equals(image.sourceUrl(), product.getCoverImage()))
                    return new MatchedImage(image.imageKey(), null, product.getCoverImage(), null);
                if (product.getGeneralImages() != null) for (String url : product.getGeneralImages()) {
                    if (Objects.equals(image.sourceUrl(), url))
                        return new MatchedImage(image.imageKey(), null, url, null);
                }
            }
        } else reason = "INVALID_IMAGE_MAPPING";
        log.warn("MODA mapping fallback requestId={} indexVersion={} productId={} imageKey={} colorId={} reason={}",
                requestId, indexVersion, product.getId(), image == null ? null : image.imageKey(),
                image == null ? null : image.colorId(), reason);
        return null;
    }

    private boolean safeUrl(String value) {
        if (value == null || value.isBlank() || value.contains("\\")) return false;
        try {
            URI uri = URI.create(value);
            if (value.startsWith("/") && !value.startsWith("//")) return !uri.isAbsolute();
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }
}
