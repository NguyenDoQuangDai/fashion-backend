package com.example.fashion_backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private PaymentMethod paymentMethod = PaymentMethod.COD;
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod value) { paymentMethod = value; }
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 16)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus value) { paymentStatus = value; }
    @Column(name = "paid_at")
    private OffsetDateTime paidAt;
    public OffsetDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(OffsetDateTime value) { paidAt = value; }
    @jakarta.persistence.OneToOne(mappedBy = "order", fetch = FetchType.LAZY)
    private VnpayPaymentEntity vnpayPayment;
    public VnpayPaymentEntity getVnpayPayment() { return vnpayPayment; }
    public void setVnpayPayment(VnpayPaymentEntity value) { vnpayPayment = value; }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "guest_id")
    private String guestId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "total")
    private BigDecimal total;

    @Column(name = "email")
    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "city")
    private String city;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemEntity> items;

    @Column(name = "delivery_formatted_address", columnDefinition = "text")
    private String deliveryFormattedAddress;

    @Column(name = "delivery_latitude", precision = 10, scale = 7)
    private BigDecimal deliveryLatitude;

    @Column(name = "delivery_longitude", precision = 10, scale = 7)
    private BigDecimal deliveryLongitude;

    @Column(name = "delivery_place_id", columnDefinition = "text")
    private String deliveryPlaceId;

    @Column(name = "delivery_address_source", length = 32)
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

    @Column(name = "items_subtotal", precision = 12, scale = 2)
    private BigDecimal itemsSubtotal;
    @Column(name = "shipping_fee", precision = 12, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;
    @Column(name = "shipping_fee_original", precision = 12, scale = 2)
    private BigDecimal shippingFeeOriginal;
    @Column(name = "shipping_fee_adjusted_at")
    private OffsetDateTime shippingFeeAdjustedAt;
    @Column(name = "shipping_distance_meters", columnDefinition = "numeric")
    private BigDecimal shippingDistanceMeters;
    @Column(name = "shipping_provider", length = 32)
    private String shippingProvider;
    @Column(name = "shipping_vehicle", length = 32)
    private String shippingVehicle;
    @Column(name = "shipping_route_status", length = 16)
    private String shippingRouteStatus;
    @Column(name = "shipping_calculated_at")
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }

    public String getGuestId() { return guestId; }
    public void setGuestId(String guestId) { this.guestId = guestId; }

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

    public List<OrderItemEntity> getItems() { return items; }
    public void setItems(List<OrderItemEntity> items) { this.items = items; }
}
