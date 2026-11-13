package com.example.fashion_backend.dto.order;

import com.example.fashion_backend.entity.PaymentMethod;
import com.example.fashion_backend.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class OrderResponse {
    private PaymentMethod paymentMethod = PaymentMethod.COD;
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod value) { paymentMethod = value; }
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus value) { paymentStatus = value; }
    private OffsetDateTime paidAt;
    public OffsetDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(OffsetDateTime value) { paidAt = value; }
    private String paymentTxnRef;
    public String getPaymentTxnRef() { return paymentTxnRef; }
    public void setPaymentTxnRef(String value) { paymentTxnRef = value; }
    private OffsetDateTime paymentExpiresAt;
    public OffsetDateTime getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(OffsetDateTime value) { paymentExpiresAt = value; }
    private boolean paymentRequiresReview;
    public boolean getPaymentRequiresReview() { return paymentRequiresReview; }
    public void setPaymentRequiresReview(boolean value) { paymentRequiresReview = value; }
    private String paymentFailureReason;
    public String getPaymentFailureReason() { return paymentFailureReason; }
    public void setPaymentFailureReason(String value) { paymentFailureReason = value; }

    private String id;
    private String status;
    private BigDecimal total;
    private String email;
    private String fullName;
    private String phone;
    private String addressLine;
    private String city;
    private String note;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<OrderItemResponse> items;

    private String deliveryFormattedAddress;

    private BigDecimal deliveryLatitude;

    private BigDecimal deliveryLongitude;

    private String deliveryPlaceId;

    private String deliveryAddressSource;

    public String getDeliveryFormattedAddress() { return deliveryFormattedAddress; }
    public void setDeliveryFormattedAddress(String deliveryFormattedAddress) { this.deliveryFormattedAddress = deliveryFormattedAddress; }

    public BigDecimal getDeliveryLatitude() { return deliveryLatitude; }
    public void setDeliveryLatitude(BigDecimal deliveryLatitude) { this.deliveryLatitude = deliveryLatitude; }

    public BigDecimal getDeliveryLongitude() { return deliveryLongitude; }
    public void setDeliveryLongitude(BigDecimal deliveryLongitude) { this.deliveryLongitude = deliveryLongitude; }

    public String getDeliveryPlaceId() { return deliveryPlaceId; }
    public void setDeliveryPlaceId(String deliveryPlaceId) { this.deliveryPlaceId = deliveryPlaceId; }

    public String getDeliveryAddressSource() { return deliveryAddressSource; }
    public void setDeliveryAddressSource(String deliveryAddressSource) { this.deliveryAddressSource = deliveryAddressSource; }

    private BigDecimal itemsSubtotal;
    private BigDecimal shippingFee = BigDecimal.ZERO;
    private BigDecimal shippingFeeOriginal;
    private OffsetDateTime shippingFeeAdjustedAt;
    private BigDecimal shippingDistanceMeters;
    private String shippingProvider;
    private String shippingVehicle;
    private String shippingRouteStatus;
    private OffsetDateTime shippingCalculatedAt;

    public BigDecimal getItemsSubtotal() { return itemsSubtotal; }
    public void setItemsSubtotal(BigDecimal value) { this.itemsSubtotal = value; }

    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal value) { this.shippingFee = value; }

    public BigDecimal getShippingFeeOriginal() { return shippingFeeOriginal; }
    public void setShippingFeeOriginal(BigDecimal value) { this.shippingFeeOriginal = value; }

    public OffsetDateTime getShippingFeeAdjustedAt() { return shippingFeeAdjustedAt; }
    public void setShippingFeeAdjustedAt(OffsetDateTime value) { this.shippingFeeAdjustedAt = value; }

    public BigDecimal getShippingDistanceMeters() { return shippingDistanceMeters; }
    public void setShippingDistanceMeters(BigDecimal value) { this.shippingDistanceMeters = value; }

    public String getShippingProvider() { return shippingProvider; }
    public void setShippingProvider(String value) { this.shippingProvider = value; }

    public String getShippingVehicle() { return shippingVehicle; }
    public void setShippingVehicle(String value) { this.shippingVehicle = value; }

    public String getShippingRouteStatus() { return shippingRouteStatus; }
    public void setShippingRouteStatus(String value) { this.shippingRouteStatus = value; }

    public OffsetDateTime getShippingCalculatedAt() { return shippingCalculatedAt; }
    public void setShippingCalculatedAt(OffsetDateTime value) { this.shippingCalculatedAt = value; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<OrderItemResponse> getItems() { return items; }
    public void setItems(List<OrderItemResponse> items) { this.items = items; }
}
