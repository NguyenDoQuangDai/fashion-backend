package com.example.fashion_backend.dto.admin;

import jakarta.validation.constraints.NotBlank;

public class UpdateOrderRequest {
    @NotBlank(message = "Trạng thái là bắt buộc")
    private String status;

    @NotBlank(message = "Tên người nhận là bắt buộc")
    private String fullName;

    @NotBlank(message = "Số điện thoại là bắt buộc")
    private String phone;

    private String email;

    @NotBlank(message = "Địa chỉ là bắt buộc")
    private String addressLine;

    @NotBlank(message = "Tỉnh/thành là bắt buộc")
    private String city;

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
}