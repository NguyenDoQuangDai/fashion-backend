package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.UserIdentityEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {
    Optional<UserIdentityEntity> findByProviderAndProviderSubject(String provider, String providerSubject);
}