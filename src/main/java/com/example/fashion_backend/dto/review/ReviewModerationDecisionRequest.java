package com.example.fashion_backend.dto.review;

import jakarta.validation.constraints.NotBlank;

public class ReviewModerationDecisionRequest {
    @NotBlank
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}