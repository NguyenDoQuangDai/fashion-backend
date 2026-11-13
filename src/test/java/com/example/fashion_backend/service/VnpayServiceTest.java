package com.example.fashion_backend.service;

import com.example.fashion_backend.config.VnpayConfig;
import com.example.fashion_backend.entity.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VnpayServiceTest {
    VnpayConfig config() { var c = new VnpayConfig(); c.setEnabled(true); c.setHashSecret("sandbox-test-secret"); return c; }

    @Test void hmacMatchesRfc4231Sha512Vector() {
        var c = config(); c.setHashSecret("\u000b".repeat(20));
        assertEquals("87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde"
                + "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854", new VnpayService(c).hmac("Hi There"));
    }

    @Test void canonicalEncodingSortsOnceAndExcludesSignatureMetadata() {
        var service = new VnpayService(config());
        assertEquals("vnp_OrderInfo=Thanh+toan+%2B+100%25+%26+%C3%81o&vnp_TxnRef=abc",
                service.canonical(Map.of("vnp_TxnRef", "abc", "vnp_OrderInfo", "Thanh toan + 100% & Áo",
                        "vnp_SecureHash", "ignored", "vnp_SecureHashType", "ignored", "extra", "ignored")));
    }

    @Test void paymentUrlUsesStoredTotalShippingSnapshotAndFixedGmt7Deadline() {
        var service = new VnpayService(config()); var order = new OrderEntity(); order.setId(UUID.randomUUID());
        var p = new VnpayPaymentEntity(); p.setOrder(order); p.setTxnRef("a".repeat(32)); p.setAmount(new BigDecimal("256000"));
        p.setCreatedAt(OffsetDateTime.parse("2026-10-22T10:00:00Z")); p.setExpiresAt(p.getCreatedAt().plusMinutes(15));
        URI url = URI.create(service.paymentUrl(p, "127.0.0.1")); var fields = new HashMap<String, String>();
        for (String pair : url.getRawQuery().split("&")) { String[] kv = pair.split("=", 2); fields.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8)); }
        assertEquals("25600000", fields.get("vnp_Amount")); assertEquals("20261022170000", fields.get("vnp_CreateDate"));
        assertEquals("20261022171500", fields.get("vnp_ExpireDate")); assertEquals("2.1.0", fields.get("vnp_Version"));
        assertTrue(service.validSignature(fields)); fields.put("vnp_Amount", "100"); assertFalse(service.validSignature(fields));
        assertFalse(fields.containsKey("vnp_HashSecret"));
    }

    @Test void ambiguousGatewayResultsAreNotFinalFailures() {
        var service = new VnpayService(config());
        for (String status : List.of("01", "04", "05", "06", "07", "09")) {
            assertEquals(PaymentStatus.PENDING, service.outcome(Map.of("vnp_ResponseCode", "07", "vnp_TransactionStatus", status)));
        }
        assertEquals(PaymentStatus.FAILED, service.outcome(Map.of("vnp_ResponseCode", "24", "vnp_TransactionStatus", "02")));
        assertEquals(PaymentStatus.PAID, service.outcome(Map.of("vnp_ResponseCode", "00", "vnp_TransactionStatus", "00")));
        assertEquals(PaymentStatus.PENDING, service.outcome(Map.of("vnp_ResponseCode", "00", "vnp_TransactionStatus", "02")));
    }

    @Test void invalidAmountsAndMissingSecretCannotCreatePayment() {
        assertThrows(IllegalArgumentException.class, () -> VnpayService.amountUnits(new BigDecimal("1.001")));
        assertThrows(IllegalArgumentException.class, () -> VnpayService.amountUnits(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> VnpayService.amountUnits(new BigDecimal("10000000000")));
        var c = new VnpayConfig(); c.setEnabled(true); assertThrows(IllegalStateException.class, c::validate);
        assertThrows(PaymentException.class, () -> new VnpayService(c).requireEnabled());
    }
}
