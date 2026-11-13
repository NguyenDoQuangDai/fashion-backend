package com.example.fashion_backend.controller;

import com.example.fashion_backend.service.GogodukService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gogoduk")
@Validated
public class GogodukController {
    private final GogodukService gogodukService;

    public GogodukController(GogodukService gogodukService) {
        this.gogodukService = gogodukService;
    }

    @GetMapping("/suggest")
    public ResponseEntity<String> suggest(
            @RequestParam @NotBlank @Size(min = 2, max = 200) String input,
            @RequestParam(defaultValue = "vi") String lang,
            @RequestParam(defaultValue = "VN") String country,
            @RequestParam(required = false) String sessionToken) {
        return proxy(gogodukService.suggest(input.trim(), lang, country, sessionToken));
    }

    @GetMapping("/resolve")
    public ResponseEntity<String> resolve(
            @RequestParam("id") @NotBlank @Size(max = 200) String placeId,
            @RequestParam(defaultValue = "vi") String lang,
            @RequestParam(required = false) String sessionToken) {
        return proxy(gogodukService.resolve(placeId.trim(), lang, sessionToken));
    }

    private ResponseEntity<String> proxy(GogodukService.UpstreamResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON);
        if (response.requestId() != null) {
            builder.header("X-Request-ID", response.requestId());
        }
        if (response.retryAfter() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, response.retryAfter());
        }
        return builder.body(response.body());
    }
}
