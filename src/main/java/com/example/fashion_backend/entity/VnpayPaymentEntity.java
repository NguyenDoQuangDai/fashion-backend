package com.example.fashion_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "vnpay_payments")
public class VnpayPaymentEntity {
    @Id @GeneratedValue @UuidGenerator
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false, unique = true)
    private OrderEntity order;
    public OrderEntity getOrder() { return order; }
    public void setOrder(OrderEntity value) { order = value; }
    @Column(name = "txn_ref", nullable = false, unique = true, length = 32)
    private String txnRef;
    public String getTxnRef() { return txnRef; }
    public void setTxnRef(String value) { txnRef = value; }
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    @Column(name = "access_token_hash", nullable = false, length = 64)
    private String accessTokenHash;
    public String getAccessTokenHash() { return accessTokenHash; }
    public void setAccessTokenHash(String value) { accessTokenHash = value; }
    @Column(name = "payment_url", columnDefinition = "text")
    private String paymentUrl;
    public String getPaymentUrl() { return paymentUrl; }
    public void setPaymentUrl(String value) { paymentUrl = value; }
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
    @Column(name = "review_after", nullable = false)
    private OffsetDateTime reviewAfter;
    public OffsetDateTime getReviewAfter() { return reviewAfter; }
    public void setReviewAfter(OffsetDateTime value) { reviewAfter = value; }
    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime value) { confirmedAt = value; }
    @Column(name = "confirmed_source", length = 16)
    private String confirmedSource;
    public String getConfirmedSource() { return confirmedSource; }
    public void setConfirmedSource(String value) { confirmedSource = value; }
    @Column(name = "response_code", length = 2)
    private String responseCode;
    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String value) { responseCode = value; }
    @Column(name = "transaction_status", length = 2)
    private String transactionStatus;
    public String getTransactionStatus() { return transactionStatus; }
    public void setTransactionStatus(String value) { transactionStatus = value; }
    @Column(name = "transaction_no", length = 32)
    private String transactionNo;
    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String value) { transactionNo = value; }
    @Column(name = "bank_code", length = 32)
    private String bankCode;
    public String getBankCode() { return bankCode; }
    public void setBankCode(String value) { bankCode = value; }
    @Column(name = "gateway_pay_date", length = 14)
    private String gatewayPayDate;
    public String getGatewayPayDate() { return gatewayPayDate; }
    public void setGatewayPayDate(String value) { gatewayPayDate = value; }
    @Column(name = "failure_reason", length = 64)
    private String failureReason;
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { failureReason = value; }
    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;
    public boolean getRequiresReview() { return requiresReview; }
    public void setRequiresReview(boolean value) { requiresReview = value; }
    public boolean needsReview(OffsetDateTime now) {
        return requiresReview || (order.getPaymentStatus() == PaymentStatus.PENDING
                && ("cancelled".equals(order.getStatus()) || !now.isBefore(reviewAfter)));
    }
}
