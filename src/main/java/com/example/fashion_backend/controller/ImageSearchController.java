package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.search.ImageSearchDtos.Response;
import com.example.fashion_backend.service.ImageSearchException;
import com.example.fashion_backend.service.ImageSearchService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/search/image")
public class ImageSearchController {
    private final ImageSearchService service;
    public ImageSearchController(ImageSearchService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> search(@RequestPart("image") MultipartFile image) {
        String requestId = UUID.randomUUID().toString();
        try {
            Response result = service.search(image, requestId);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (ImageSearchException ex) {
            return ResponseEntity.status(ex.getStatus()).body(Map.of(
                    "code", ex.getCode(), "message", ex.getMessage(), "requestId", requestId));
        }
    }
}
