package com.example.fashion_backend.service;

import com.example.fashion_backend.config.VnpayConfig;
import com.example.fashion_backend.dto.payment.PaymentDtos.*;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.Order;
import com.example.fashion_backend.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final OrderService orderService;
    private final OrderRepository orders;
    private final VnpayPaymentRepository payments;
    private final PaymentHistoryRepository history;
    private final VnpayService gateway;
    private final VnpayConfig config;
    private final MailService mail;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PaymentService(OrderService orderService, OrderRepository orders, VnpayPaymentRepository payments,
            PaymentHistoryRepository history, VnpayService gateway, VnpayConfig config, MailService mail, Clock clock) {
        this.orderService = orderService; this.orders = orders; this.payments = payments;
        this.history = history; this.gateway = gateway; this.config = config; this.mail = mail; this.clock = clock;
    }

    @Transactional
    public CheckoutResult checkout(Order request, UUID userId) {
        gateway.requireEnabled();
        // The outer transaction includes stock, order and payment. No partially created checkout.
        OrderEntity order = orderService.createOrder(request, userId);
        VnpayService.amountUnits(order.getTotal());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var payment = new VnpayPaymentEntity();
        payment.setOrder(order); payment.setTxnRef(UUID.randomUUID().toString().replace("-", ""));
        payment.setAmount(order.getTotal()); payment.setAccessTokenHash(tokenHash(token));
        payment.setCreatedAt(now()); payment.setExpiresAt(payment.getCreatedAt().plusMinutes(config.getExpireMinutes()));
        payment.setReviewAfter(payment.getExpiresAt().plusMinutes(config.getReviewGraceMinutes()));
        payments.save(payment); order.setVnpayPayment(payment);
        return new CheckoutResult(order, payment.getTxnRef(), token);
    }

    @Transactional
    public PaymentUrl create(UUID orderId, UUID userId, String token, String ip) {
        gateway.requireEnabled();
        OrderEntity order = lockOrder(orderId);
        VnpayPaymentEntity payment = payments.findByOrderId(orderId).orElseThrow(this::notFound);
        authorize(payment, userId, token);
        if (order.getPaymentMethod() != PaymentMethod.VNPAY || order.getPaymentStatus() != PaymentStatus.PENDING
                || !"processing".equals(order.getStatus())) {
            throw new PaymentException(409, "PAYMENT_NOT_PAYABLE", "Đơn hàng không còn được phép thanh toán.");
        }
        if (!now().isBefore(payment.getExpiresAt())) throw new PaymentException(409, "PAYMENT_EXPIRED", "Đã hết thời gian thanh toán. Đơn đang chờ xác minh kết quả.");
        checkSnapshot(payment, order);
        if (payment.getPaymentUrl() == null) payment.setPaymentUrl(gateway.paymentUrl(payment, ip));
        return new PaymentUrl(payment.getPaymentUrl(), payment.getTxnRef(), payment.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public Status status(String txnRef, UUID userId, String token) {
        VnpayPaymentEntity payment = payments.findByTxnRef(txnRef).orElseThrow(this::notFound);
        authorize(payment, userId, token);
        return view(payment);
    }

    @Transactional(readOnly = true)
    public ReturnResult verifyReturn(Map<String, String> fields, UUID userId, String token) {
        validateFields(fields);
        if (!gateway.validSignature(fields) || !gateway.validMerchant(fields)) {
            throw new PaymentException(400, "INVALID_RETURN_SIGNATURE", "Không xác minh được dữ liệu trả về từ VNPAY.");
        }
        VnpayPaymentEntity payment = payments.findByTxnRef(fields.get("vnp_TxnRef")).orElseThrow(this::notFound);
        authorize(payment, userId, token);
        if (!validAmount(fields, payment)) throw new PaymentException(400, "INVALID_RETURN_AMOUNT", "Số tiền trả về không khớp giao dịch.");
        String outcome = gateway.outcome(fields) == PaymentStatus.PAID ? "SUCCESS"
                : "24".equals(fields.get("vnp_ResponseCode")) ? "CANCELLED"
                : gateway.outcome(fields) == PaymentStatus.FAILED ? "FAILED" : "PENDING";
        // Read-only: even a correctly signed Return cannot confirm payment or release inventory.
        return new ReturnResult(view(payment), outcome);
    }

    @Transactional
    public IpnReply ipn(Map<String, String> fields) {
        validateFields(fields);
        if (!gateway.validSignature(fields)) return reply("97", "Invalid Checksum");
        if (!gateway.validMerchant(fields)) return reply("99", "Invalid Merchant");
        UUID orderId = payments.findOrderIdByTxnRef(fields.get("vnp_TxnRef")).orElse(null);
        if (orderId == null) return reply("01", "Order not Found");
        OrderEntity order = lockOrder(orderId);
        // Fetch mutable payment state only after acquiring the shared order lock.
        VnpayPaymentEntity payment = payments.findByTxnRef(fields.get("vnp_TxnRef")).orElseThrow(this::notFound);
        if (!validAmount(fields, payment) || order.getPaymentMethod() != PaymentMethod.VNPAY) return reply("04", "Invalid Amount");
        String eventKey = tokenHash(gateway.canonical(fields));
        if (history.existsByPaymentIdAndSourceAndEventKey(payment.getId(), "IPN", eventKey)) return reply("02", "Order already confirmed");
        PaymentStatus outcome = gateway.outcome(fields), before = order.getPaymentStatus();
        payment.setResponseCode(fields.get("vnp_ResponseCode"));
        payment.setTransactionStatus(fields.get("vnp_TransactionStatus"));
        payment.setTransactionNo(fields.get("vnp_TransactionNo"));
        payment.setBankCode(fields.get("vnp_BankCode"));
        payment.setGatewayPayDate(fields.get("vnp_PayDate"));
        String note;
        if (before != PaymentStatus.PENDING) {
            if (outcome == before) return reply("02", "Order already confirmed");
            payment.setRequiresReview(true);
            note = "IPN mâu thuẫn hoặc chưa rõ sau kết quả đã kết thúc; giữ nguyên kết luận, cần đối soát.";
        } else if (outcome == PaymentStatus.PAID && "cancelled".equals(order.getStatus())) {
            payment.setRequiresReview(true);
            note = "VNPAY báo thành công khi đơn đã hủy; giữ đơn đã hủy và chuyển đối soát thủ công.";
        } else if (outcome == PaymentStatus.PENDING) {
            payment.setRequiresReview(true);
            note = "IPN chưa xác định kết quả cuối cùng; giữ PENDING và tồn kho.";
        } else {
            confirm(payment, order, outcome, "IPN", "24".equals(fields.get("vnp_ResponseCode")) ? "CANCELLED" : "GATEWAY_FAILED");
            note = outcome == PaymentStatus.PAID ? "IPN xác nhận thanh toán thành công." : "IPN xác nhận giao dịch kết thúc không thành công.";
        }
        audit(payment, before, order.getPaymentStatus(), "IPN", eventKey, null, null, note, gateway.canonical(fields));
        return reply("00", "Confirm Success");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminDetails(UUID orderId) {
        var payment = payments.findByOrderId(orderId).orElseThrow(this::notFound);
        return Map.of("payment", view(payment), "responseCode", Objects.toString(payment.getResponseCode(), ""),
                "transactionStatus", Objects.toString(payment.getTransactionStatus(), ""),
                "transactionNo", Objects.toString(payment.getTransactionNo(), ""), "history",
                history.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream().map(h -> new History(h.getId(), h.getSource(),
                        h.getPreviousStatus(), h.getNewStatus(), h.getActorId(), h.getActorName(), h.getCreatedAt(), h.getNote(), h.getEvidence())).toList());
    }

    @Transactional
    public Status resolve(UUID orderId, ResolveRequest request, UUID actorId, String actorName) {
        if (actorId == null || actorName == null || actorName.isBlank()) throw new PaymentException(403, "ADMIN_REQUIRED", "Yêu cầu tài khoản quản trị.");
        if (!request.sandboxChecked() || request.note() == null || request.note().isBlank() || request.note().trim().length() > 2000
                || request.status() == null || request.status() == PaymentStatus.PENDING) {
            throw new PaymentException(400, "INVALID_RESOLUTION", "Phải kiểm tra Sandbox, chọn PAID/FAILED và nhập ghi chú đối soát.");
        }
        var order = lockOrder(orderId);
        var payment = payments.findByOrderId(orderId).orElseThrow(this::notFound);
        if (order.getPaymentMethod() != PaymentMethod.VNPAY || order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new PaymentException(409, "PAYMENT_FINAL", "Thanh toán đã kết thúc; không được sửa kết luận qua luồng đối soát này.");
        }
        if (!payment.needsReview(now()) && !"cancelled".equals(order.getStatus())) {
            throw new PaymentException(409, "REVIEW_NOT_DUE", "Giao dịch chưa đến thời điểm đối soát.");
        }
        confirm(payment, order, request.status(), "ADMIN", "ADMIN_CONFIRMED_FAILURE");
        audit(payment, PaymentStatus.PENDING, request.status(), "ADMIN", UUID.randomUUID().toString(), actorId,
                actorName, request.note().trim(), "Admin xác nhận đã kiểm tra giao dịch trên VNPAY Sandbox.");
        return view(payment);
    }

    private void confirm(VnpayPaymentEntity payment, OrderEntity order, PaymentStatus outcome, String source, String failureReason) {
        order.setPaymentStatus(outcome); order.setUpdatedAt(now());
        payment.setConfirmedAt(now()); payment.setConfirmedSource(source); payment.setRequiresReview(false);
        payment.setFailureReason(outcome == PaymentStatus.FAILED ? failureReason : null);
        if (outcome == PaymentStatus.PAID) {
            order.setPaidAt(now());
            if ("cancelled".equals(order.getStatus())) payment.setRequiresReview(true);
            if (order.getEmail() != null && !order.getEmail().isBlank()) {
                mail.sendAfterCommit(order.getEmail(), "[5SMan] Đã nhận thanh toán đơn " + order.getId(),
                        "<p>Đã ghi nhận thanh toán VNPAY cho đơn " + order.getId() + ".</p>");
            }
        } else orderService.cancelAfterFailedPayment(order);
        orders.save(order);
    }

    private void audit(VnpayPaymentEntity payment, PaymentStatus before, PaymentStatus after, String source, String key,
            UUID actorId, String actorName, String note, String evidence) {
        var entry = new PaymentHistoryEntity(); entry.setPayment(payment); entry.setPreviousStatus(before); entry.setNewStatus(after);
        entry.setSource(source); entry.setEventKey(key); entry.setActorId(actorId); entry.setActorName(actorName);
        entry.setCreatedAt(now()); entry.setNote(note); entry.setEvidence(evidence); history.save(entry);
    }

    private Status view(VnpayPaymentEntity payment) {
        var order = payment.getOrder(); boolean expired = !now().isBefore(payment.getExpiresAt());
        return new Status(order.getId(), payment.getTxnRef(), order.getPaymentStatus(), order.getStatus(), order.getTotal(),
                payment.getExpiresAt(), expired, payment.needsReview(now()), payment.getFailureReason(),
                config.isEnabled() && !expired && "processing".equals(order.getStatus()) && order.getPaymentStatus() == PaymentStatus.PENDING,
                order.getPaidAt());
    }

    private void authorize(VnpayPaymentEntity payment, UUID userId, String token) {
        var owner = payment.getOrder().getUser();
        if (userId != null && owner != null && userId.equals(owner.getId())) return;
        if (token != null && token.length() <= 128 && MessageDigest.isEqual(tokenHash(token).getBytes(StandardCharsets.US_ASCII),
                payment.getAccessTokenHash().getBytes(StandardCharsets.US_ASCII))) return;
        throw new PaymentException(403, "PAYMENT_ACCESS_DENIED", "Bạn không có quyền truy cập giao dịch này. Vui lòng đăng nhập đúng tài khoản hoặc dùng trình duyệt đã đặt hàng.");
    }

    private boolean validAmount(Map<String, String> fields, VnpayPaymentEntity payment) {
        String amount = fields.get("vnp_Amount");
        return amount != null && amount.matches("[0-9]{1,12}") && new java.math.BigInteger(amount).toString().equals(VnpayService.amountUnits(payment.getAmount()))
                && payment.getOrder().getTotal() != null && payment.getAmount().compareTo(payment.getOrder().getTotal()) == 0;
    }
    private void checkSnapshot(VnpayPaymentEntity payment, OrderEntity order) {
        if (payment.getAmount().compareTo(order.getTotal()) != 0) throw new PaymentException(409, "PAYMENT_AMOUNT_CHANGED", "Tổng đơn không khớp giao dịch. Vui lòng liên hệ cửa hàng.");
    }
    private void validateFields(Map<String, String> fields) {
        if (fields == null || fields.size() > 40 || fields.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().length() > 64 || e.getValue() == null || e.getValue().length() > 1024)
                || !Objects.toString(fields.get("vnp_TxnRef"), "").matches("[A-Za-z0-9]{1,100}")
                || !Objects.toString(fields.get("vnp_ResponseCode"), "").matches("[0-9]{2}")
                || !Objects.toString(fields.get("vnp_TransactionStatus"), "").matches("[0-9]{2}")) {
            throw new PaymentException(400, "INVALID_VNPAY_DATA", "Dữ liệu VNPAY không hợp lệ.");
        }
        for (String key : List.of("vnp_TransactionNo", "vnp_BankCode", "vnp_PayDate")) {
            int max = "vnp_PayDate".equals(key) ? 14 : 32;
            if (fields.get(key) != null && fields.get(key).length() > max) throw new PaymentException(400, "INVALID_VNPAY_DATA", "Dữ liệu VNPAY không hợp lệ.");
        }
    }
    private OrderEntity lockOrder(UUID id) { return orders.findLockedById(id).orElseThrow(this::notFound); }
    private PaymentException notFound() { return new PaymentException(404, "PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch."); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private IpnReply reply(String code, String message) { return new IpnReply(code, message); }
    static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Cannot hash payment token"); }
    }
}
