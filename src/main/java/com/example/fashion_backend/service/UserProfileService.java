package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.user.UserProfileResponse;
import com.example.fashion_backend.dto.user.UserProfileUpdateRequest;
import com.example.fashion_backend.entity.UserEntity;
import com.example.fashion_backend.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return mapToResponse(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UserProfileUpdateRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (request.getPriceMin() != null && request.getPriceMax() != null
                && request.getPriceMin().compareTo(request.getPriceMax()) > 0) {
            throw new IllegalArgumentException("priceMin must be <= priceMax");
        }
        user.setFullName(request.getFullName());
        user.setPhone(request.getPhone());
        user.setGender(request.getGender());
        user.setPreferredCategories(request.getPreferredCategories());
        user.setPriceMin(request.getPriceMin());
        user.setPriceMax(request.getPriceMax());
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        return mapToResponse(user);
    }

    private UserProfileResponse mapToResponse(UserEntity user) {
        UserProfileResponse response = new UserProfileResponse();
        response.setId(user.getId().toString());
        response.setEmail(user.getEmail());
        response.setFullName(user.getFullName());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setPreferredCategories(user.getPreferredCategories());
        response.setPriceMin(user.getPriceMin());
        response.setPriceMax(user.getPriceMax());
        return response;
    }
}
