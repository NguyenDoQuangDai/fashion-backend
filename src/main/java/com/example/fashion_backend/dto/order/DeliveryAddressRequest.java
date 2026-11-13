package com.example.fashion_backend.dto.order;

import java.math.BigDecimal;

public record DeliveryAddressRequest(
        String formattedAddress,
        String providerPlaceId,
        BigDecimal latitude,
        BigDecimal longitude,
        String sessionToken,
        String source,
        String selectionToken) {
    public DeliveryAddressRequest(String formattedAddress, String providerPlaceId, BigDecimal latitude,
            BigDecimal longitude, String sessionToken) {
        this(formattedAddress, providerPlaceId, latitude, longitude, sessionToken, "GOGODUK", null);
    }
}
