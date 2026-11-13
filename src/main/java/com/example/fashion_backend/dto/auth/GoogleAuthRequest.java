package com.example.fashion_backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

public class GoogleAuthRequest {
    @NotBlank
    private String credential;

    private String guestCartId;

    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }

    public String getGuestCartId() { return guestCartId; }
    public void setGuestCartId(String guestCartId) { this.guestCartId = guestCartId; }
}