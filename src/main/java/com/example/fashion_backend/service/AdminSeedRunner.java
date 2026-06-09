package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.RoleEntity;
import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.repository.RoleRepository;
import com.example.fashion_backend.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeedRunner implements CommandLineRunner {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    public AdminSeedRunner(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            return;
        }
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }

        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> {
                    RoleEntity role = new RoleEntity();
                    role.setName("ADMIN");
                    return roleRepository.save(role);
                });

        UserEntity admin = new UserEntity();
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setFullName("Admin");
        admin.setEnabled(true);
        admin.setCreatedAt(OffsetDateTime.now());
        admin.setUpdatedAt(OffsetDateTime.now());
        admin.setRoles(List.of(adminRole));
        userRepository.save(admin);
    }
}
