package com.example.fashion_backend.controller.admin;

import com.example.fashion_backend.dto.admin.UpdateUserRolesRequest;
import com.example.fashion_backend.dto.admin.UpdateUserStatusRequest;
import com.example.fashion_backend.dto.admin.UserAdminResponse;
import com.example.fashion_backend.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ResponseEntity<List<UserAdminResponse>> list() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<UserAdminResponse> updateRoles(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserRolesRequest request) {
        return ResponseEntity.ok(adminUserService.updateRoles(UUID.fromString(id), request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<UserAdminResponse> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(adminUserService.updateStatus(UUID.fromString(id), request));
    }
}
