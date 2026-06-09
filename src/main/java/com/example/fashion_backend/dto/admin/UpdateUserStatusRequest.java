package com.example.fashion_backend.dto.admin;

import jakarta.validation.constraints.NotNull;

public class UpdateUserStatusRequest {
    @NotNull
    private Boolean enabled;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
