package com.example.fashion_backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class UpdateOrderRequest {
    @NotBlank(message = "Trạng thái là bắt buộc")
    private String status;

    @NotBlank(message = "Tên người nhận là bắt buộc")
    private String fullName;

    @NotBlank(message = "Số điện thoại là bắt buộc")
    private String phone;

    private String email;

    private String addressLine;

    // Validated against the persisted order: provider results may have no city.
    private String city;

    @Size(max = 255, message = "Ghi chú không được vượt quá 255 ký tự")
    private String note;

    // Kept only to reject stale clients that still attempt to change the checkout fee.
    private BigDecimal shippingFee;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }
}
