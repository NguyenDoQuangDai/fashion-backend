package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.OrderItemResponse;
import com.example.fashion_backend.dto.order.OrderResponse;
import com.example.fashion_backend.dto.order.CheckoutQuoteResponse;
import com.example.fashion_backend.dto.admin.UpdateOrderRequest;
import com.example.fashion_backend.entity.OrderEntity;
import com.example.fashion_backend.entity.PaymentMethod;
import com.example.fashion_backend.entity.PaymentStatus;
import com.example.fashion_backend.entity.OrderItemEntity;
import com.example.fashion_backend.entity.ProductEntity;
import com.example.fashion_backend.entity.ProductColorVariantEntity;
import com.example.fashion_backend.entity.ProductSizeVariantEntity;
import com.example.fashion_backend.model.CartItem;
import com.example.fashion_backend.model.Order;
import com.example.fashion_backend.repository.OrderRepository;
import com.example.fashion_backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final MailService mailService;
    private final DeliveryAddressService deliveryAddressService;
    private final ShippingService shippingService;
    @org.springframework.beans.factory.annotation.Autowired private InventoryService inventory;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, MailService mailService, DeliveryAddressService deliveryAddressService, ShippingService shippingService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.mailService = mailService;
        this.deliveryAddressService = deliveryAddressService;
        this.shippingService = shippingService;
    }

    @Transactional
    public OrderEntity createOrder(Order request, UUID userId) {
        if (request.getPaymentMethod() == null) throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
        // Resolve, route and price from the server before mutating stock or saving anything.
        var prepared = prepareCheckout(request);
        inventory.lockProducts(prepared.items().stream().map(l -> l.product().getId()).toList());
        var locked = inventory.lockSizes(prepared.items().stream().map(l -> l.size().getId()).toList());
        for (var line : prepared.items()) {
            var current = locked.get(line.size().getId());
            var price = current.getPrice() != null ? current.getPrice() : line.product().getPrice();
            if (price == null || price.compareTo(line.item().getPriceSnapshot()) != 0)
                throw new IllegalArgumentException("Product price changed; request a new quote");
            line.item().setSizeVariantId(current.getId());
        }
        var quote = prepared.response();
        if (request.getExpectedShippingFee() == null || request.getExpectedTotal() == null) {
            throw new ShippingException(400, "QUOTE_REQUIRED", "Vui lòng lấy báo giá trước khi xác nhận đặt hàng.");
        }
        if (request.getExpectedShippingFee().compareTo(quote.shippingFee()) != 0
                || request.getExpectedTotal().compareTo(quote.total()) != 0) {
            throw new ShippingException(409, "CHECKOUT_QUOTE_CHANGED",
                    "Phí vận chuyển hoặc giá sản phẩm đã thay đổi. Vui lòng kiểm tra tổng tiền mới và xác nhận lại.", null, quote);
        }
        var delivery = prepared.delivery();
        OrderEntity order = new OrderEntity();
        order.setGuestId(request.getClientId());
        if (userId != null) {
            com.example.fashion_backend.entity.UserEntity userRef = new com.example.fashion_backend.entity.UserEntity();
            userRef.setId(userId);
            order.setUser(userRef);
        }
        order.setStatus("processing");
        order.setPaymentMethod(request.getPaymentMethod());
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setEmail(request.getEmail());
        order.setFullName(request.getName());
        order.setPhone(request.getPhone());
        order.setAddressLine(delivery.formattedAddress());
        order.setCity(delivery.city());
        order.setDeliveryFormattedAddress(delivery.formattedAddress());
        order.setDeliveryLatitude(delivery.latitude());
        order.setDeliveryLongitude(delivery.longitude());
        order.setDeliveryPlaceId(delivery.placeId());
        order.setDeliveryAddressSource(delivery.source());
        order.setNote(request.getNote());
        OffsetDateTime now = OffsetDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        List<OrderItemEntity> items = new ArrayList<>();
        for (var line : prepared.items()) {
            line.item().setOrder(order);
            items.add(line.item());

        }
        order.setItems(items);
        order.setItemsSubtotal(quote.itemsSubtotal());
        order.setShippingFee(quote.shippingFee());
        order.setShippingDistanceMeters(quote.distanceMeters());
        order.setShippingProvider(prepared.shipping().provider());
        order.setShippingVehicle(prepared.shipping().vehicle());
        order.setShippingRouteStatus(prepared.shipping().routeStatus());
        order.setShippingCalculatedAt(now);
        order.setTotal(quote.total());
        OrderEntity saved = orderRepository.save(order);
        java.util.Map<Long, Integer> debit = new java.util.TreeMap<>();
        for (var line : prepared.items()) debit.merge(line.size().getId(), line.item().getQuantity(), Math::addExact);
        debit.forEach((id, qty) -> inventory.change(locked.get(id), "debit:" + saved.getId(), "DEBIT", -qty));
        if (userId != null && saved.getEmail() != null && !saved.getEmail().isBlank()) {
            String subject = saved.getPaymentMethod() == PaymentMethod.VNPAY
                ? "[5SMan] Đơn hàng #" + saved.getId() + " đang chờ thanh toán"
                : "🎉 Đơn hàng #" + saved.getId() + " tại 5SMan đã được xác nhận";
            String formattedTotal = saved.getTotal() != null ? String.format("%,d VNĐ", saved.getTotal().longValue()) : "Chưa xác định";

            String body = """
            <!DOCTYPE html>
            <html>
            <body style="margin:0; padding:0; background-color:#f4f5f7; font-family:'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;">
              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background-color:#f4f5f7; padding:40px 0;">
                <tr>
                  <td align="center">
                    <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background-color:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 10px 25px rgba(0,0,0,0.08);">
                      <tr>
                        <td style="background-color:#0f172a; padding:30px; text-align:center;">
                          <h1 style="color:#ffffff; margin:0; font-size:26px; letter-spacing:3px; font-weight:800;">5SMAN</h1>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:40px 30px; color:#334155;">
                          <div style="text-align:center; margin-bottom:20px;">
                            <span style="background-color:#dcfce7; color:#15803d; font-size:13px; font-weight:bold; padding:6px 16px; border-radius:20px;">Đã xác nhận</span>
                          </div>
                          <h2 style="margin-top:0; color:#0f172a; font-size:20px; text-align:center;">Cảm ơn bạn đã đặt hàng!</h2>
                          <p style="font-size:15px;">Xin chào <strong>%s</strong>,</p>
                          <p style="font-size:15px; line-height:1.6;">Đơn hàng của bạn đã được chúng tôi tiếp nhận thành công và đang trong quá trình chuẩn bị.</p>

                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background-color:#f8fafc; border-radius:8px; padding:20px; margin:25px 0;">
                            <tr>
                              <td style="padding:8px 0; color:#64748b; font-size:14px;">Mã đơn hàng:</td>
                              <td style="padding:8px 0; color:#0f172a; font-weight:bold; font-size:14px; text-align:right;">#%s</td>
                            </tr>
                            <tr>
                              <td style="padding:8px 0; color:#64748b; font-size:14px;">Địa chỉ giao hàng:</td>
                              <td style="padding:8px 0; color:#0f172a; font-weight:600; font-size:14px; text-align:right;">%s</td>
                            </tr>
                            <tr style="border-top:1px solid #e2e8f0;">
                              <td style="padding:12px 0 0 0; color:#0f172a; font-weight:bold; font-size:16px;">Tổng cộng:</td>
                              <td style="padding:12px 0 0 0; color:#2563eb; font-weight:800; font-size:18px; text-align:right;">%s</td>
                            </tr>
                          </table>

                          <p style="font-size:14px; color:#64748b; text-align:center;">Chúng tôi sẽ gửi thêm thông báo khi đơn hàng bắt đầu được vận chuyển.</p>
                        </td>
                      </tr>
                      <tr>
                        <td style="background-color:#f8fafc; padding:20px; text-align:center; font-size:12px; color:#94a3b8; border-top:1px solid #e2e8f0;">
                          &copy; 5SMan Fashion Store. All rights reserved.
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(
                saved.getFullName(),
                saved.getId(),
                saved.getDeliveryFormattedAddress(),
                formattedTotal
            );
            if (saved.getPaymentMethod() == PaymentMethod.VNPAY) {
                body = body.replace("Đã xác nhận", "Chờ thanh toán VNPAY")
                    .replace("đang trong quá trình chuẩn bị", "đang chờ thanh toán VNPAY; chỉ giao hàng sau khi xác nhận đã thanh toán");
            }
            mailService.sendAfterCommit(saved.getEmail(), subject, body);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public CheckoutQuoteResponse quote(Order request) {
        return prepareCheckout(request).response();
    }

    private PreparedCheckout prepareCheckout(Order request) {
        return CheckoutDeadline.run(() -> prepareCheckoutWithinDeadline(request));
    }

    private PreparedCheckout prepareCheckoutWithinDeadline(Order request) {
        var delivery = deliveryAddressService.resolve(request.getDeliveryAddress());
        var shipping = shippingService.quote(delivery);
        if (request.getItems() == null || request.getItems().isEmpty() || request.getItems().size() > 100) {
            throw new IllegalArgumentException("Giỏ hàng phải có từ 1 đến 100 dòng sản phẩm.");
        }
        List<PreparedItem> lines = new ArrayList<>();
        java.util.Map<String, ProductEntity> products = new java.util.HashMap<>();
        java.util.Map<ProductSizeVariantEntity, Long> quantities = new java.util.HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : request.getItems()) {
            if (item == null || item.getProductId() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException("Sản phẩm hoặc số lượng không hợp lệ.");
            }
            ProductEntity product = products.computeIfAbsent(item.getProductId(), id -> productRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Sản phẩm không còn tồn tại: " + id)));
            var color = findColorVariant(product, item.getColorId(), item.getColorName());
            var size = findSizeVariant(color, item.getSizeId(), item.getSizeName());
            if (color == null || size == null) throw new IllegalArgumentException("Vui lòng chọn màu và kích thước hợp lệ.");
            long quantity = quantities.merge(size, (long) item.getQuantity(), Long::sum);
            if (size.getInventory() == null || quantity > size.getInventory()) {
                throw new IllegalArgumentException("Không đủ tồn kho cho sản phẩm " + item.getProductId());
            }
            BigDecimal price = size.getPrice() != null ? size.getPrice() : product.getPrice();
            if (price == null || price.signum() < 0) throw new IllegalArgumentException("Sản phẩm chưa có giá hợp lệ.");
            OrderItemEntity entity = new OrderItemEntity();
            entity.setProductId(product.getId()); entity.setQuantity(item.getQuantity());
            entity.setTitleSnapshot(product.getTitle()); entity.setColorId(color.getColorId());
            entity.setColorSnapshot(color.getColorName()); entity.setSizeId(size.getSizeId());
            entity.setSizeSnapshot(size.getSizeName()); entity.setPriceSnapshot(price);
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            lines.add(new PreparedItem(product, size, entity));
        }
        if (subtotal.add(shipping.shippingFee()).compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException("Tổng tiền đơn hàng vượt giới hạn cho phép.");
        }
        return new PreparedCheckout(delivery, shipping, lines, subtotal);
    }

    private record PreparedItem(ProductEntity product, ProductSizeVariantEntity size, OrderItemEntity item) {}
    private record PreparedCheckout(DeliveryAddressService.ResolvedAddress delivery, ShippingService.ShippingQuote shipping,
            List<PreparedItem> items, BigDecimal subtotal) {
        CheckoutQuoteResponse response() {
            return new CheckoutQuoteResponse(delivery, shipping.distanceMeters(), subtotal, shipping.shippingFee(),
                    subtotal.add(shipping.shippingFee()), shipping.routeStatus(), shipping.provider(), shipping.vehicle());
        }
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(UUID userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(this::mapToResponse)
                .orElse(null);
    }

    @Transactional
    public OrderResponse updateOrder(UUID orderId, UpdateOrderRequest request) {
        OrderEntity entity = orderRepository.findLockedById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        String currentStatus = normalizeStatus(entity.getStatus());
        if (isFinalStatus(currentStatus)) {
            validateFinalOrderNoteUpdate(entity, request, currentStatus);
            entity.setNote(normalizeOptionalText(request.getNote()));
            entity.setUpdatedAt(OffsetDateTime.now());
            return mapToResponse(orderRepository.save(entity));
        }

        String nextStatus = normalizeStatus(request.getStatus());
        if (nextStatus.isBlank()) {
            nextStatus = currentStatus;
        }

        if (!isTransitionAllowed(currentStatus, nextStatus)) {
            throw new IllegalArgumentException("Invalid status transition");
        }

        if (entity.getPaymentMethod() == PaymentMethod.VNPAY && entity.getPaymentStatus() != PaymentStatus.PAID
                && ("shipping".equals(nextStatus) || "completed".equals(nextStatus))) {
            throw new PaymentException(409, "PAYMENT_REQUIRED", "Đơn VNPAY phải thanh toán thành công trước khi giao hoặc hoàn tất.");
        }
        validateShippingUpdate(entity, request);
        validateShippingFeeUnchanged(entity, request.getShippingFee());
        boolean statusChanged = !Objects.equals(currentStatus, nextStatus);
        entity.setStatus(nextStatus);
        applyShippingFields(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());

        if (statusChanged && "cancelled".equals(nextStatus) && (currentStatus == null || !currentStatus.equalsIgnoreCase("cancelled"))) {
            restoreInventory(entity);
            if (entity.getPaymentMethod() == PaymentMethod.COD && entity.getPaymentStatus() == PaymentStatus.PENDING) {
                entity.setPaymentStatus(PaymentStatus.FAILED);
            }
        }
        if (statusChanged && "completed".equals(nextStatus) && (currentStatus == null || !currentStatus.equalsIgnoreCase("completed"))) {
            applySoldCount(entity);
            if (entity.getPaymentMethod() == PaymentMethod.COD) {
                entity.setPaymentStatus(PaymentStatus.PAID);
                entity.setPaidAt(OffsetDateTime.now());
            }
        }

        OrderEntity saved = orderRepository.save(entity);
        if (statusChanged) {
            sendStatusNotification(saved, nextStatus);
        }
        return mapToResponse(saved);
    }

    @Transactional
    public OrderResponse updateStatus(UUID orderId, String status) {
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setStatus(status);
        return updateOrder(orderId, request);
    }

    private void validateShippingUpdate(OrderEntity entity, UpdateOrderRequest request) {
        if ((request.getAddressLine() != null && !Objects.equals(request.getAddressLine().trim(), entity.getAddressLine()))
                || (request.getCity() != null && !Objects.equals(normalizeOptionalText(request.getCity()), normalizeOptionalText(entity.getCity())))) {
            throw new IllegalArgumentException("Không thể sửa địa chỉ giao hàng của đơn hàng.");
        }
    }

    private void validateFinalOrderNoteUpdate(OrderEntity entity, UpdateOrderRequest request, String currentStatus) {
        validateShippingUpdate(entity, request);
        BigDecimal currentFee = entity.getShippingFee() == null ? BigDecimal.ZERO : entity.getShippingFee();
        if (request.getNote() == null
                || (request.getStatus() != null && !currentStatus.equals(normalizeStatus(request.getStatus())))
                || (request.getFullName() != null && !Objects.equals(request.getFullName().trim(), entity.getFullName()))
                || (request.getPhone() != null && !Objects.equals(request.getPhone().trim(), entity.getPhone()))
                || (request.getEmail() != null && !Objects.equals(normalizeOptionalText(request.getEmail()), normalizeOptionalText(entity.getEmail())))
                || (request.getShippingFee() != null && request.getShippingFee().compareTo(currentFee) != 0)) {
            throw new IllegalArgumentException("Đơn hàng đã hoàn tất hoặc hủy chỉ cho phép sửa ghi chú.");
        }
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateShippingFeeUnchanged(OrderEntity entity, BigDecimal requestedFee) {
        if (requestedFee == null) return;
        BigDecimal currentFee = entity.getShippingFee() == null ? BigDecimal.ZERO : entity.getShippingFee();
        if (requestedFee.compareTo(currentFee) != 0) {
            throw new IllegalArgumentException("Phí vận chuyển chỉ do checkout tính, không thể sửa tại trang quản trị.");
        }
    }

    private void applyShippingFields(OrderEntity entity, UpdateOrderRequest request) {
        if (request.getFullName() != null) {
            entity.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null) {
            entity.setPhone(request.getPhone().trim());
        }
        if (request.getEmail() != null) {
            String email = request.getEmail().trim();
            entity.setEmail(email.isBlank() ? null : email);
        }
        if (request.getNote() != null) {
            entity.setNote(normalizeOptionalText(request.getNote()));
        }
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isFinalStatus(String status) {
        String normalized = normalizeStatus(status);
        return "completed".equals(normalized) || "cancelled".equals(normalized);
    }

    private void sendStatusNotification(OrderEntity saved, String normalized) {
        if (saved.getUser() == null || saved.getEmail() == null || saved.getEmail().isBlank()) {
            return;
        }

        String statusLabel = switch (normalized) {
            case "shipping" -> "Đang vận chuyển 🚚";
            case "completed" -> "Đã hoàn thành ✅";
            case "cancelled" -> "Đã bị hủy ❌";
            default -> normalized;
        };

        String subject = "[5SMan] Cập nhật trạng thái đơn hàng #" + saved.getId();
        String body = """
        <!DOCTYPE html>
        <html>
        <body style="margin:0; padding:0; background-color:#f4f5f7; font-family:'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;">
          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background-color:#f4f5f7; padding:40px 0;">
            <tr>
              <td align="center">
                <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background-color:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 10px 25px rgba(0,0,0,0.08);">
                  <tr>
                    <td style="background-color:#0f172a; padding:30px; text-align:center;">
                      <h1 style="color:#ffffff; margin:0; font-size:26px; letter-spacing:3px; font-weight:800;">5SMAN</h1>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:40px 30px; color:#334155;">
                      <h2 style="margin-top:0; color:#0f172a; font-size:20px; text-align:center;">Cập nhật đơn hàng</h2>
                      <p style="font-size:15px;">Xin chào <strong>%s</strong>,</p>
                      <p style="font-size:15px; line-height:1.6;">Đơn hàng <strong>#%s</strong> của bạn vừa có cập nhật trạng thái mới:</p>

                      <div style="text-align:center; margin:25px 0;">
                        <span style="background-color:#e0f2fe; color:#0369a1; font-size:16px; font-weight:bold; padding:10px 24px; border-radius:8px; display:inline-block;">%s</span>
                      </div>

                      <p style="font-size:14px; color:#64748b; text-align:center;">Nếu có bất kỳ thắc mắc nào, vui lòng phản hồi email này để được hỗ trợ.</p>
                    </td>
                  </tr>
                  <tr>
                    <td style="background-color:#f8fafc; padding:20px; text-align:center; font-size:12px; color:#94a3b8; border-top:1px solid #e2e8f0;">
                      &copy; 5SMan Fashion Store. All rights reserved.
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.formatted(saved.getFullName(), saved.getId(), statusLabel);

        mailService.sendAfterCommit(saved.getEmail(), subject, body);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderForUser(UUID orderId, UUID userId) {
        return orderRepository.findByIdAndUserId(orderId, userId).map(this::mapToResponse).orElse(null);
    }

    // Caller holds the order row lock. Never restore twice and never reopen a final order.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelAfterFailedPayment(OrderEntity entity) {
        entity = orderRepository.findLockedById(entity.getId()).orElseThrow();
        if (!"cancelled".equals(entity.getStatus()) && !"completed".equals(entity.getStatus())) {
            entity.setStatus("cancelled");
            restoreInventory(entity);
        }
    }

    public OrderResponse mapToResponse(OrderEntity entity) {
        OrderResponse response = new OrderResponse();
        response.setId(entity.getId().toString());
        response.setStatus(entity.getStatus());
        response.setPaymentMethod(entity.getPaymentMethod());
        response.setPaymentStatus(entity.getPaymentStatus());
        response.setPaidAt(entity.getPaidAt());
        var payment = entity.getVnpayPayment();
        if (payment != null) {
            response.setPaymentTxnRef(payment.getTxnRef());
            response.setPaymentExpiresAt(payment.getExpiresAt());
            response.setPaymentRequiresReview(payment.needsReview(OffsetDateTime.now()));
            response.setPaymentFailureReason(payment.getFailureReason());
        }
        response.setTotal(entity.getTotal());
        response.setItemsSubtotal(entity.getItemsSubtotal());
        response.setShippingFee(entity.getShippingFee());
        response.setShippingFeeOriginal(entity.getShippingFeeOriginal());
        response.setShippingFeeAdjustedAt(entity.getShippingFeeAdjustedAt());
        response.setShippingDistanceMeters(entity.getShippingDistanceMeters());
        response.setShippingProvider(entity.getShippingProvider());
        response.setShippingVehicle(entity.getShippingVehicle());
        response.setShippingRouteStatus(entity.getShippingRouteStatus());
        response.setShippingCalculatedAt(entity.getShippingCalculatedAt());
        response.setEmail(entity.getEmail());
        response.setFullName(entity.getFullName());
        response.setPhone(entity.getPhone());
        response.setAddressLine(entity.getAddressLine());
        response.setCity(entity.getCity());
        response.setNote(entity.getNote());
        response.setDeliveryFormattedAddress(entity.getDeliveryFormattedAddress());
        response.setDeliveryLatitude(entity.getDeliveryLatitude());
        response.setDeliveryLongitude(entity.getDeliveryLongitude());
        response.setDeliveryPlaceId(entity.getDeliveryPlaceId());
        response.setDeliveryAddressSource(entity.getDeliveryAddressSource());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());

        List<OrderItemResponse> items = new ArrayList<>();
        if (entity.getItems() != null) {
            for (OrderItemEntity item : entity.getItems()) {
                OrderItemResponse itemResponse = new OrderItemResponse();
                itemResponse.setProductId(item.getProductId());
                itemResponse.setTitle(item.getTitleSnapshot());
                itemResponse.setColor(item.getColorSnapshot());
                itemResponse.setSize(item.getSizeSnapshot());
                itemResponse.setQuantity(item.getQuantity());
                itemResponse.setPrice(item.getPriceSnapshot());
                items.add(itemResponse);
            }
        }
        response.setItems(items);
        return response;
    }

    private ProductColorVariantEntity findColorVariant(ProductEntity product, String colorId, String colorName) {
        if (product.getColors() == null || product.getColors().isEmpty()) return null;
        for (ProductColorVariantEntity color : product.getColors()) {
            if (colorId != null && Objects.equals(colorId, color.getColorId())) return color;
        }
        for (ProductColorVariantEntity color : product.getColors()) {
            if (colorName != null && Objects.equals(colorName, color.getColorName())) return color;
        }
        return null;
    }

    private ProductSizeVariantEntity findSizeVariant(ProductColorVariantEntity colorVariant, String sizeId, String sizeName) {
        if (colorVariant == null || colorVariant.getSizes() == null || colorVariant.getSizes().isEmpty()) return null;
        for (ProductSizeVariantEntity size : colorVariant.getSizes()) {
            if (sizeId != null && Objects.equals(sizeId, size.getSizeId())) return size;
        }
        for (ProductSizeVariantEntity size : colorVariant.getSizes()) {
            if (sizeName != null && Objects.equals(sizeName, size.getSizeName())) return size;
        }
        return null;
    }

    private boolean isTransitionAllowed(String current, String next) {
        if (next == null || next.isBlank()) return false;
        String from = current == null ? "" : current.toLowerCase(Locale.ROOT).trim();
        if (from.equals(next)) return true;
        return switch (from) {
            case "processing" -> next.equals("shipping") || next.equals("completed") || next.equals("cancelled");
            case "shipping" -> next.equals("completed") || next.equals("cancelled");
            case "completed", "cancelled" -> false;
            default -> false;
        };
    }

    private void applySoldCount(OrderEntity order) {
        if (order.getItems() == null) return;
        inventory.lockProducts(order.getItems().stream().map(OrderItemEntity::getProductId).toList());
        for (OrderItemEntity item : order.getItems()) {
            productRepository.findById(item.getProductId()).ifPresent(product -> {
                int current = product.getSoldCount() == null ? 0 : product.getSoldCount();
                int qty = item.getQuantity() == null ? 0 : item.getQuantity();
                product.setSoldCount(current + qty);
                productRepository.save(product);
            });
        }
    }

    private void restoreInventory(OrderEntity order) {
        if (order.getItems() == null || order.getItems().isEmpty()) return;
        inventory.lockProducts(order.getItems().stream().map(OrderItemEntity::getProductId).toList());
        java.util.Map<Long, Integer> quantities = new java.util.TreeMap<>();
        for (var item : order.getItems()) {
            Long id = item.getSizeVariantId();
            if (id == null) {
                if (item.getColorId() == null || item.getSizeId() == null || item.getColorId().isBlank() || item.getSizeId().isBlank())
                    throw new IllegalStateException("Legacy order variant identity requires review");
                var product = productRepository.findById(item.getProductId()).orElseThrow();
                var matches = product.getColors().stream().filter(c -> Objects.equals(c.getColorId(), item.getColorId()))
                    .flatMap(c -> c.getSizes().stream()).filter(v -> Objects.equals(v.getSizeId(), item.getSizeId())).toList();
                if (matches.size() != 1) throw new IllegalStateException("Legacy order size mapping requires review");
                id = matches.get(0).getId();
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) throw new IllegalStateException("Invalid restore quantity");
            quantities.merge(id, item.getQuantity(), Math::addExact);
        }
        var sizes = inventory.lockSizes(quantities.keySet());
        for (var item : order.getItems()) {
            if (item.getSizeVariantId() != null && !sizes.get(item.getSizeVariantId()).getColorVariant().getProduct().getId().equals(item.getProductId()))
                throw new IllegalStateException("Order size parent mismatch");
        }
        quantities.forEach((id, qty) -> inventory.change(sizes.get(id), "restore:" + order.getId(), "RESTORE", qty));
    }
}
