package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.user.UserProfileResponse;
import com.example.fashion_backend.dto.user.UserProfileUpdateRequest;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.UserProfileService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {
    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        UUID userId = principal.getUser().getId();
        return ResponseEntity.ok(userProfileService.getProfile(userId));
    }

    @PutMapping
    public ResponseEntity<UserProfileResponse> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        UUID userId = principal.getUser().getId();
        return ResponseEntity.ok(userProfileService.updateProfile(userId, request));
    }
}
