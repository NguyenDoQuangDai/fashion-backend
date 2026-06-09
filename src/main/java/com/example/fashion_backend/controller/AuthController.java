package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.auth.AuthResponse;
import com.example.fashion_backend.dto.auth.ForgotRequest;
import com.example.fashion_backend.dto.auth.LoginRequest;
import com.example.fashion_backend.dto.auth.MessageResponse;
import com.example.fashion_backend.dto.auth.RefreshRequest;
import com.example.fashion_backend.dto.auth.RegisterRequest;
import com.example.fashion_backend.dto.auth.ResetRequest;
import com.example.fashion_backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.logout(request));
    }

    @PostMapping("/forgot")
    public ResponseEntity<MessageResponse> forgot(@Valid @RequestBody ForgotRequest request) {
        return ResponseEntity.ok(authService.forgot(request));
    }

    @PostMapping("/reset")
    public ResponseEntity<MessageResponse> reset(@Valid @RequestBody ResetRequest request) {
        return ResponseEntity.ok(authService.reset(request));
    }

    @GetMapping("/verify")
    public ResponseEntity<MessageResponse> verify(@RequestParam("token") String token) {
        return ResponseEntity.ok(authService.verifyEmail(token));
    }
}
