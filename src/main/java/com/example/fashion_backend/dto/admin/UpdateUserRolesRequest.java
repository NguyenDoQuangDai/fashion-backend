package com.example.fashion_backend.dto.admin;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;

public class UpdateUserRolesRequest {
    @NotEmpty
    private List<String> roles;

    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}
