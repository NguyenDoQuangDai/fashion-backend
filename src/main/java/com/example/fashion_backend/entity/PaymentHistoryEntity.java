package com.example.fashion_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "payment_history")
public class PaymentHistoryEntity {
    @Id @GeneratedValue @UuidGenerator
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id", nullable = false)
    private VnpayPaymentEntity payment;
    public VnpayPaymentEntity getPayment() { return payment; }
    public void setPayment(VnpayPaymentEntity value) { payment = value; }
    @Column(nullable = false, length = 16)
    private String source;
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;
    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { eventKey = value; }
    @Enumerated(EnumType.STRING) @Column(name = "previous_status", nullable = false, length = 16)
    private PaymentStatus previousStatus;
    public PaymentStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(PaymentStatus value) { previousStatus = value; }
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false, length = 16)
    private PaymentStatus newStatus;
    public PaymentStatus getNewStatus() { return newStatus; }
    public void setNewStatus(PaymentStatus value) { newStatus = value; }
    @Column(name = "actor_id")
    private UUID actorId;
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID value) { actorId = value; }
    @Column(name = "actor_name")
    private String actorName;
    public String getActorName() { return actorName; }
    public void setActorName(String value) { actorName = value; }
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    @Column(nullable = false, columnDefinition = "text")
    private String note;
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    @Column(columnDefinition = "text")
    private String evidence;
    public String getEvidence() { return evidence; }
    public void setEvidence(String value) { evidence = value; }
}
