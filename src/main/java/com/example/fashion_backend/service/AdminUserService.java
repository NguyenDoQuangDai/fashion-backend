package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.admin.UpdateUserRolesRequest;
import com.example.fashion_backend.dto.admin.UpdateUserStatusRequest;
import com.example.fashion_backend.dto.admin.UserAdminResponse;
import com.example.fashion_backend.entity.RoleEntity;
import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.repository.RoleRepository;
import com.example.fashion_backend.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public AdminUserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional(readOnly = true)
    public List<UserAdminResponse> listUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public UserAdminResponse updateRoles(UUID userId, UpdateUserRolesRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<RoleEntity> roles = request.getRoles().stream()
                .map(this::findOrCreateRole)
                .collect(Collectors.toList());
        user.setRoles(roles);
        userRepository.save(user);
        return mapToResponse(user);
    }

    @Transactional
    public UserAdminResponse updateStatus(UUID userId, UpdateUserStatusRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setEnabled(request.getEnabled());
        userRepository.save(user);
        return mapToResponse(user);
    }

    private RoleEntity findOrCreateRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> {
                    RoleEntity role = new RoleEntity();
                    role.setName(name);
                    return roleRepository.save(role);
                });
    }

    private UserAdminResponse mapToResponse(UserEntity user) {
        UserAdminResponse response = new UserAdminResponse();
        response.setId(user.getId().toString());
        response.setEmail(user.getEmail());
        response.setFullName(user.getFullName());
        response.setEnabled(user.getEnabled());
        response.setRoles(user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(RoleEntity::getName)
                .toList());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }
}
