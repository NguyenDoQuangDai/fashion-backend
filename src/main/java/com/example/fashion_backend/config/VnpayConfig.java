package com.example.fashion_backend.config;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.vnpay")
public class VnpayConfig {
    private boolean enabled;
    private String tmnCode = "PBHJI61M";
    private String hashSecret = "";
    private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String returnUrl = "http://localhost:5173/payment-result";
    private String ipnUrl = "https://pawing-coastland-splurge.ngrok-free.dev/api/payments/vnpay/ipn";
    private int expireMinutes = 15;
    private int reviewGraceMinutes = 5;

    @PostConstruct
    public void validate() {
        if (expireMinutes < 1 || reviewGraceMinutes < 0) throw new IllegalStateException("Invalid VNPAY timeout configuration");
        if (enabled && (hashSecret == null || hashSecret.isBlank() || tmnCode == null || !tmnCode.matches("[A-Za-z0-9]{8}"))) {
            throw new IllegalStateException("VNPAY requires VNPAY_HASH_SECRET and a valid VNPAY_TMN_CODE");
        }
        if (!"https://sandbox.vnpayment.vn/paymentv2/vpcpay.html".equals(paymentUrl)) {
            throw new IllegalStateException("This integration only supports the VNPAY Sandbox payment URL");
        }
        if (!returnUrl.startsWith("http://localhost:5173/") || returnUrl.contains("?")) {
            throw new IllegalStateException("VNPAY Return URL must be a fixed React localhost:5173 route without query parameters");
        }
    }

    @Bean public Clock paymentClock() { return Clock.systemUTC(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getTmnCode() { return tmnCode; }
    public void setTmnCode(String value) { tmnCode = value; }
    public String getHashSecret() { return hashSecret; }
    public void setHashSecret(String value) { hashSecret = value; }
    public String getPaymentUrl() { return paymentUrl; }
    public void setPaymentUrl(String value) { paymentUrl = value; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String value) { returnUrl = value; }
    public String getIpnUrl() { return ipnUrl; }
    public void setIpnUrl(String value) { ipnUrl = value; }
    public int getExpireMinutes() { return expireMinutes; }
    public void setExpireMinutes(int value) { expireMinutes = value; }
    public int getReviewGraceMinutes() { return reviewGraceMinutes; }
    public void setReviewGraceMinutes(int value) { reviewGraceMinutes = value; }
}
