package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.OrderItemResponse;
import com.example.fashion_backend.dto.order.OrderResponse;
import com.example.fashion_backend.dto.admin.UpdateOrderRequest;
import com.example.fashion_backend.entity.OrderEntity;
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
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final MailService mailService;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, MailService mailService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.mailService = mailService;
    }

    @Transactional
    public OrderEntity createOrder(Order request, UUID userId) {
        OrderEntity order = new OrderEntity();
        order.setGuestId(request.getClientId());
        if (userId != null) {
            com.example.fashion_backend.entity.UserEntity userRef = new com.example.fashion_backend.entity.UserEntity();
            userRef.setId(userId);
            order.setUser(userRef);
        }
        order.setStatus("processing");
        order.setEmail(request.getEmail());
        order.setFullName(request.getName());
        order.setPhone(request.getPhone());
        order.setAddressLine(request.getAddress());
        order.setCity(request.getCity());
        order.setNote(request.getNote());
        OffsetDateTime now = OffsetDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        List<OrderItemEntity> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean hasPrice = false;

        if (request.getItems() != null) {
            for (CartItem item : request.getItems()) {
                OrderItemEntity entity = new OrderItemEntity();
                entity.setOrder(order);
                entity.setProductId(item.getProductId());
                entity.setQuantity(item.getQuantity());

                Optional<ProductEntity> product = productRepository.findById(item.getProductId());
                if (product.isPresent()) {
                    ProductEntity p = product.get();
                    ProductColorVariantEntity colorVariant = findColorVariant(p, item.getColorId(), item.getColorName());
                    ProductSizeVariantEntity sizeVariant = findSizeVariant(colorVariant, item.getSizeId(), item.getSizeName());
                    if (colorVariant == null || sizeVariant == null) {
                        throw new IllegalArgumentException("Color/size is required for product " + item.getProductId());
                    }
                    if (sizeVariant.getInventory() != null && item.getQuantity() > sizeVariant.getInventory()) {
                        throw new IllegalArgumentException("Not enough inventory for product " + item.getProductId());
                    }
                    if (sizeVariant.getInventory() != null) {
                        sizeVariant.setInventory(sizeVariant.getInventory() - item.getQuantity());
                        productRepository.save(p);
                    }
                    entity.setTitleSnapshot(p.getTitle());
                    entity.setColorId(colorVariant.getColorId());
                    entity.setColorSnapshot(colorVariant.getColorName());
                    entity.setSizeId(sizeVariant.getSizeId());
                    entity.setSizeSnapshot(sizeVariant.getSizeName());
                    BigDecimal price = sizeVariant.getPrice() != null ? sizeVariant.getPrice() : p.getPrice();
                    if (price != null) {
                        entity.setPriceSnapshot(price);
                        total = total.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
                        hasPrice = true;
                    }
                }
                items.add(entity);
            }
        }

        order.setItems(items);
        order.setTotal(hasPrice ? total : null);
        OrderEntity saved = orderRepository.save(order);
        if (userId != null && saved.getEmail() != null && !saved.getEmail().isBlank()) {
            String subject = "🎉 Đơn hàng #" + saved.getId() + " tại 5SMan đã được xác nhận";
            String body = """
                Xin chào %s,
                
                Cảm ơn bạn đã tin tưởng mua sắm tại 5SMan! 
                Đơn hàng của bạn đã được chúng tôi tiếp nhận và đang trong quá trình xử lý.
                
                Thông tin đơn hàng:
                - Mã đơn hàng: %s
                - Tổng tiền: %s
                - Trạng thái: Đang xử lý
                - Địa chỉ nhận hàng: %s, %s
                
                Chúng tôi sẽ thông báo cho bạn ngay khi đơn hàng được gửi đi.
                
                Trân trọng,
                Đội ngũ 5SMan.
                """.formatted(
                    saved.getFullName(), 
                    saved.getId(), 
                    saved.getTotal() != null ? saved.getTotal().toString() + " VNĐ" : "Chưa xác định",
                    saved.getAddressLine(),
                    saved.getCity()
                );
            mailService.send(saved.getEmail(), subject, body);
        }
        return saved;
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
        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        String currentStatus = normalizeStatus(entity.getStatus());
        if (isFinalStatus(currentStatus)) {
            throw new IllegalArgumentException("Order status cannot be changed");
        }

        String nextStatus = normalizeStatus(request.getStatus());
        if (nextStatus.isBlank()) {
            nextStatus = currentStatus;
        }

        if (!isTransitionAllowed(currentStatus, nextStatus)) {
            throw new IllegalArgumentException("Invalid status transition");
        }

        boolean statusChanged = !Objects.equals(currentStatus, nextStatus);
        entity.setStatus(nextStatus);
        applyShippingFields(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());

        if (statusChanged && "cancelled".equals(nextStatus) && (currentStatus == null || !currentStatus.equalsIgnoreCase("cancelled"))) {
            restoreInventory(entity);
        }
        if (statusChanged && "completed".equals(nextStatus) && (currentStatus == null || !currentStatus.equalsIgnoreCase("completed"))) {
            applySoldCount(entity);
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
        if (request.getAddressLine() != null) {
            entity.setAddressLine(request.getAddressLine().trim());
        }
        if (request.getCity() != null) {
            entity.setCity(request.getCity().trim());
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
            case "shipping" -> "đang trên đường vận chuyển 🚚";
            case "completed" -> "đã hoàn thành thành công ✅";
            case "cancelled" -> "đã bị hủy ❌";
            default -> normalized;
        };

        String subject = "[5SMan] Cập nhật trạng thái đơn hàng #" + saved.getId();
        String body = """
            Xin chào %s,
            
            Đơn hàng #%s của bạn tại 5SMan hiện đã chuyển sang trạng thái: %s.
            
            Nếu bạn có bất kỳ thắc mắc nào, vui lòng liên hệ với bộ phận chăm sóc khách hàng của chúng tôi.
            
            Cảm ơn bạn đã đồng hành cùng 5SMan!
            
            Trân trọng,
            Đội ngũ 5SMan.
            """.formatted(saved.getFullName(), saved.getId(), statusLabel);

        mailService.send(saved.getEmail(), subject, body);
    }

    private OrderResponse mapToResponse(OrderEntity entity) {
        OrderResponse response = new OrderResponse();
        response.setId(entity.getId().toString());
        response.setStatus(entity.getStatus());
        response.setTotal(entity.getTotal());
        response.setEmail(entity.getEmail());
        response.setFullName(entity.getFullName());
        response.setPhone(entity.getPhone());
        response.setAddressLine(entity.getAddressLine());
        response.setCity(entity.getCity());
        response.setNote(entity.getNote());
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
        if (order.getItems() == null) return;
        for (OrderItemEntity item : order.getItems()) {
            productRepository.findById(item.getProductId()).ifPresent(product -> {
                ProductColorVariantEntity colorVariant = findColorVariant(product, item.getColorId(), item.getColorSnapshot());
                ProductSizeVariantEntity sizeVariant = findSizeVariant(colorVariant, item.getSizeId(), item.getSizeSnapshot());
                if (sizeVariant == null || item.getQuantity() == null) return;
                if (sizeVariant.getInventory() != null) {
                    sizeVariant.setInventory(sizeVariant.getInventory() + item.getQuantity());
                    productRepository.save(product);
                }
            });
        }
    }
}
