package com.example.fashion_backend.controller;

import com.example.fashion_backend.service.ReviewMediaStorage;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-images")
public class ReviewImageController {
    private final ReviewMediaStorage storage;

    public ReviewImageController(ReviewMediaStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/{key:.+}")
    public ResponseEntity<InputStreamResource> get(@PathVariable String key) throws IOException {
        return ResponseEntity.ok()
                .contentType(contentTypeFor(key))
                .body(new InputStreamResource(storage.open(key)));
    }

    private MediaType contentTypeFor(String key) {
        if (key.endsWith(".jpg") || key.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (key.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (key.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
