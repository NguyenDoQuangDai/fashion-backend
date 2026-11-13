package com.example.fashion_backend.controller;

import com.example.fashion_backend.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/addresses")
@Validated
public class AddressController {
    private final AddressLookupService addresses;
    private final FallbackStore store;
    public AddressController(AddressLookupService addresses, FallbackStore store) { this.addresses = addresses; this.store = store; }
    @GetMapping("/suggest")
    public ResponseEntity<String> suggest(@RequestParam @NotBlank @Size(min=3,max=200) String input,
            @RequestParam @NotBlank @Size(max=200) String sessionToken, HttpServletRequest request) {
        limit(request); return json(CheckoutDeadline.run(() -> addresses.suggest(input, sessionToken)).toString());
    }
    @GetMapping("/resolve")
    public ResponseEntity<String> resolve(@RequestParam("id") @NotBlank @Size(max=200) String placeId,
            @RequestParam(defaultValue="GOGODUK") String source,
            @RequestParam(required=false) @Size(max=100) String selectionToken,
            @RequestParam @NotBlank @Size(max=200) String sessionToken, HttpServletRequest request) {
        limit(request);
        JsonNode result = CheckoutDeadline.run(() -> addresses.resolve(placeId, source, selectionToken, sessionToken));
        return json("{\"result\":" + result + "}");
    }
    private ResponseEntity<String> json(String body) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body); }
    private void limit(HttpServletRequest request) {
        // Trust the socket peer, not an arbitrary client-supplied forwarding header.
        if (!store.acquire("address:client:" + GeoapifyService.hash(request.getRemoteAddr()), 60, 60000))
            throw new AddressException(429, "ADDRESS_RATE_LIMITED", "Tìm kiếm đang quá tải. Vui lòng thử lại sau.", 60);
    }
}
