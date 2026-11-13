package com.example.fashion_backend.service;

import com.example.fashion_backend.config.VnpayConfig;
import com.example.fashion_backend.entity.PaymentStatus;
import com.example.fashion_backend.entity.VnpayPaymentEntity;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class VnpayService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final Set<String> FINAL_FAILURE_CODES = Set.of("09", "10", "11", "12", "13", "24", "51", "65", "75", "79");
    private final VnpayConfig config;
    public VnpayService(VnpayConfig config) { this.config = config; }

    public void requireEnabled() {
        if (!config.isEnabled() || config.getHashSecret() == null || config.getHashSecret().isBlank()) {
            throw new PaymentException(503, "VNPAY_UNAVAILABLE", "Thanh toán VNPAY chưa được cấu hình. Vui lòng chọn COD.");
        }
    }

    public String paymentUrl(VnpayPaymentEntity payment, String ip) {
        requireEnabled();
        var params = new TreeMap<String, String>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", config.getTmnCode());
        params.put("vnp_Amount", amountUnits(payment.getAmount()));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", payment.getTxnRef());
        params.put("vnp_OrderInfo", "Thanh toan don hang " + payment.getOrder().getId().toString().replace("-", ""));
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", config.getReturnUrl());
        params.put("vnp_IpAddr", ip);
        params.put("vnp_CreateDate", DATE.format(payment.getCreatedAt()));
        params.put("vnp_ExpireDate", DATE.format(payment.getExpiresAt()));
        String data = canonical(params);
        return config.getPaymentUrl() + "?" + data + "&vnp_SecureHash=" + hmac(data);
    }

    public static String amountUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0.");
        String value;
        try { value = amount.movePointRight(2).toBigIntegerExact().toString(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Số tiền thanh toán không hợp lệ."); }
        if (value.length() > 12) throw new IllegalArgumentException("Số tiền vượt giới hạn VNPAY.");
        return value;
    }

    public boolean validSignature(Map<String, String> fields) {
        String supplied = fields.get("vnp_SecureHash");
        if (config.getHashSecret() == null || config.getHashSecret().isBlank() || supplied == null || !supplied.matches("[0-9a-fA-F]{128}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(hmac(canonical(fields))), HexFormat.of().parseHex(supplied));
    }

    // Encode each decoded name/value once; omit empty fields and checksum metadata.
    public String canonical(Map<String, String> fields) {
        if (fields == null || fields.size() > 40) throw new IllegalArgumentException("Invalid VNPAY fields");
        return new TreeMap<>(fields).entrySet().stream()
                .filter(e -> e.getKey().startsWith("vnp_") && !Set.of("vnp_SecureHash", "vnp_SecureHashType").contains(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }

    public boolean validMerchant(Map<String, String> fields) { return config.getTmnCode().equals(fields.get("vnp_TmnCode")); }

    public PaymentStatus outcome(Map<String, String> fields) {
        String response = fields.get("vnp_ResponseCode"), status = fields.get("vnp_TransactionStatus");
        if ("00".equals(response) && "00".equals(status)) return PaymentStatus.PAID;
        if ("02".equals(status) && FINAL_FAILURE_CODES.contains(response)) return PaymentStatus.FAILED;
        return PaymentStatus.PENDING;
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Cannot sign VNPAY request"); }
    }
}
