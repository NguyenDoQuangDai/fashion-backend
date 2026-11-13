package com.example.fashion_backend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductViewRequest(@NotBlank @Size(max = 255) String productId) {}
