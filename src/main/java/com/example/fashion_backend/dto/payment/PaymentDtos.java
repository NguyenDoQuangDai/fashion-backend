package com.example.fashion_backend.dto.payment;

import com.example.fashion_backend.entity.PaymentStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}
    public record CreateRequest(@NotNull UUID orderId) {}
    public record ResolveRequest(@NotNull PaymentStatus status, @NotBlank @Size(max = 2000) String note,
                                 @AssertTrue(message = "Phải kiểm tra giao dịch trên VNPAY Sandbox trước khi xác nhận") boolean sandboxChecked) {}
    public record CheckoutResult(com.example.fashion_backend.entity.OrderEntity order, String txnRef, String paymentAccessToken) {}
    public record PaymentUrl(String paymentUrl, String txnRef, OffsetDateTime expiresAt) {}
    public record Status(UUID orderId, String txnRef, PaymentStatus paymentStatus, String orderStatus,
                         BigDecimal total, OffsetDateTime expiresAt, boolean expired, boolean requiresReview,
                         String failureReason, boolean canPay, OffsetDateTime paidAt) {}
    public record ReturnResult(Status payment, String gatewayOutcome) {}
    public record IpnReply(String RspCode, String Message) {}
    public record History(UUID id, String source, PaymentStatus previousStatus, PaymentStatus newStatus,
                          UUID actorId, String actorName, OffsetDateTime createdAt, String note, String evidence) {}
}
