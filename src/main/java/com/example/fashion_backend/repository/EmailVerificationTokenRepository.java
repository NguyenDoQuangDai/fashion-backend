package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.EmailVerificationTokenEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, Long> {
    Optional<EmailVerificationTokenEntity> findByToken(String token);
}
