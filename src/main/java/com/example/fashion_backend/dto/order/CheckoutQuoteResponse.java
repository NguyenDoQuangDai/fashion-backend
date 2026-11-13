package com.example.fashion_backend.dto.order;

import java.math.BigDecimal;
import com.example.fashion_backend.service.DeliveryAddressService.ResolvedAddress;

public record CheckoutQuoteResponse(ResolvedAddress deliveryAddress, BigDecimal distanceMeters,
        BigDecimal itemsSubtotal, BigDecimal shippingFee, BigDecimal total, String shippingRouteStatus, String shippingProvider, String shippingVehicle) {
    public CheckoutQuoteResponse(ResolvedAddress address, BigDecimal distance, BigDecimal subtotal,
            BigDecimal fee, BigDecimal total, String status) {
        this(address,distance,subtotal,fee,total,status,"GOGODUK","motobike");
    }
}
