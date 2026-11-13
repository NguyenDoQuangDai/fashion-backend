package com.example.fashion_backend.service;

import com.example.fashion_backend.controller.CheckoutController;
import com.example.fashion_backend.dto.admin.UpdateOrderRequest;
import com.example.fashion_backend.dto.order.OrderResponse;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.CartItem;
import com.example.fashion_backend.model.Order;
import com.example.fashion_backend.repository.OrderRepository;
import com.example.fashion_backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderAddressTest {
    @org.junit.jupiter.api.BeforeEach void bindInventory() { InventoryTestSupport.bind(service, products); }
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final MailService mail = mock(MailService.class);
    private final DeliveryAddressService addresses = mock(DeliveryAddressService.class);
    private final ShippingService shipping = mock(ShippingService.class);
    private final OrderService service = new OrderService(orders, products, mail, addresses, shipping);

    @BeforeEach void configureSave() {
        when(shipping.quote(any())).thenReturn(new ShippingService.ShippingQuote(new BigDecimal("5000"), BigDecimal.ZERO));
        when(orders.save(any())).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            if (order.getId() == null) order.setId(UUID.randomUUID());
            return order;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createsGuestAndMemberSnapshotsWithServerPrice(boolean member) {
        when(addresses.resolve(any())).thenReturn(new DeliveryAddressService.ResolvedAddress(
                "Ngõ 12, Hà Nội", "place-1", new BigDecimal("21.0288654"), new BigDecimal("105.7819884"), null));
        ProductSizeVariantEntity size = new ProductSizeVariantEntity();
        size.setSizeId("M"); size.setSizeName("M"); size.setInventory(5); size.setPrice(new BigDecimal("125000"));
        ProductColorVariantEntity color = new ProductColorVariantEntity();
        color.setColorId("black"); color.setColorName("Đen"); color.setSizes(Set.of(size));
        ProductEntity product = new ProductEntity();
        product.setId("p1"); product.setTitle("Áo nam"); product.setColors(List.of(color));
        when(products.findById("p1")).thenReturn(Optional.of(product));
        CartItem item = new CartItem("p1", 2); item.setColorId("black"); item.setSizeId("M");
        Order request = new Order(); request.setItems(List.of(item)); request.setAddress("Forged address");
        request.setCity("Forged city"); request.setNote("Số nhà 12, gọi trước");
        request.setName("Nguyễn Văn A");
        if (member) request.setEmail("test@example.invalid");
        request.setExpectedShippingFee(BigDecimal.ZERO);
        request.setExpectedTotal(new BigDecimal("250000"));
        UUID userId = member ? UUID.randomUUID() : null;
        OrderEntity saved = service.createOrder(request, userId);
        assertEquals("Ngõ 12, Hà Nội", saved.getDeliveryFormattedAddress());
        assertEquals(saved.getDeliveryFormattedAddress(), saved.getAddressLine());
        assertEquals("GOGODUK", saved.getDeliveryAddressSource());
        assertEquals("place-1", saved.getDeliveryPlaceId());
        assertEquals(new BigDecimal("21.0288654"), saved.getDeliveryLatitude());
        assertEquals(new BigDecimal("105.7819884"), saved.getDeliveryLongitude());
        assertNull(saved.getCity());
        assertEquals(request.getNote(), saved.getNote());
        assertEquals(new BigDecimal("250000"), saved.getTotal());
        assertEquals(3, size.getInventory());
        assertEquals(userId, saved.getUser() == null ? null : saved.getUser().getId());
        OrderResponse response = service.mapToResponse(saved);
        assertEquals(saved.getTotal(), response.getTotal());
        assertEquals(saved.getDeliveryLatitude(), response.getDeliveryLatitude());
        if (member) {
            verify(mail).sendAfterCommit(eq("test@example.invalid"), anyString(), argThat(body ->
                    body.contains("Ngõ 12, Hà Nội") && !body.contains("Hà Nội, null")));
        }
    }

    @Test void providerFailureDoesNotTouchInventoryOrdersOrMail() {
        when(addresses.resolve(any())).thenThrow(new DeliveryAddressService.AddressResolutionException("Unavailable"));
        assertThrows(DeliveryAddressService.AddressResolutionException.class, () -> service.createOrder(new Order(), null));
        verifyNoInteractions(products, orders, mail);
    }

    @Test void checkoutReturnsPersistedResponseRatherThanRequest() {
        OrderService mockService = mock(OrderService.class);
        Order request = new Order(); request.setAddress("client");
        OrderEntity saved = existing(true);
        OrderResponse response = service.mapToResponse(saved);
        when(mockService.createOrder(request, null)).thenReturn(saved);
        when(mockService.mapToResponse(saved)).thenReturn(response);
        Map<?, ?> body = (Map<?, ?>) new CheckoutController(mockService, mock(PaymentService.class), mock(CartService.class)).checkout(request, null).getBody();
        assertSame(response, body.get("order"));
        assertEquals(saved.getId().toString(), body.get("orderId"));
        assertEquals(saved.getTotal(), ((OrderResponse) body.get("order")).getTotal());
    }

    @Test void readsLegacyWithoutResolvingOrRecalculating() {
        OrderEntity legacy = existing(false);
        legacy.setNote("Ghi chú lịch sử"); legacy.setCity("Hà Nội");
        when(orders.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        var response = service.getOrder(legacy.getId());
        assertEquals(legacy.getAddressLine(), response.getAddressLine());
        assertEquals(legacy.getCity(), response.getCity());
        assertEquals(legacy.getNote(), response.getNote());
        assertEquals(legacy.getStatus(), response.getStatus());
        assertEquals(legacy.getTotal(), response.getTotal());
        assertNull(response.getDeliveryLatitude());
        verifyNoInteractions(addresses, products, mail);
        verify(orders, never()).save(any());
    }

    @Test void newOrderWithoutCityCanUpdateStatusAndContact() {
        OrderEntity order = existing(true);
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setStatus("shipping"); request.setPhone("0900000000");
        request.setAddressLine(order.getAddressLine()); request.setCity("");
        service.updateOrder(order.getId(), request);
        assertEquals("shipping", order.getStatus());
        assertEquals("0900000000", order.getPhone());
        assertNull(order.getCity());
        assertEquals("Snapshot", order.getDeliveryFormattedAddress());
        verifyNoInteractions(addresses);
    }

    @Test void rejectsAddressOrCityEditsBeforeChangingStatus() {
        OrderEntity order = existing(true);
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setStatus("shipping"); request.setAddressLine("different");
        assertThrows(IllegalArgumentException.class, () -> service.updateOrder(order.getId(), request));
        request.setAddressLine(order.getAddressLine()); request.setCity("different");
        assertThrows(IllegalArgumentException.class, () -> service.updateOrder(order.getId(), request));
        assertEquals("processing", order.getStatus());
        verify(orders, never()).save(any());
    }

    @Test void legacyAddressIsAlsoReadOnly() {
        OrderEntity order = existing(false);
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setAddressLine("New legacy text"); request.setCity("Hà Nội");
        assertThrows(IllegalArgumentException.class, () -> service.updateOrder(order.getId(), request));
        assertEquals("Snapshot", order.getAddressLine());
        assertNull(order.getDeliveryAddressSource());
        assertEquals(new BigDecimal("987654.32"), order.getTotal());
        verify(orders, never()).save(any());
    }

    @Test void adminCanEditNoteWithoutChangingFeeTotalOrDeliveryAddress() {
        OrderEntity order = existing(true);
        order.setItemsSubtotal(new BigDecimal("980654.32"));
        order.setShippingFee(new BigDecimal("7000"));
        order.setNote("Original note");
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setNote("Updated note");
        var response = service.updateOrder(order.getId(), request);
        assertEquals("Updated note", response.getNote());
        assertEquals(new BigDecimal("7000"), response.getShippingFee());
        assertNull(response.getShippingFeeOriginal());
        assertNull(response.getShippingFeeAdjustedAt());
        assertEquals(new BigDecimal("987654.32"), response.getTotal());
        assertEquals("Snapshot", response.getDeliveryFormattedAddress());
    }

    @Test void legacyNoteEditPreservesHistoricalFeeAndTotal() {
        OrderEntity order = existing(false);
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setNote("Updated note");
        var response = service.updateOrder(order.getId(), request);
        assertNull(response.getItemsSubtotal());
        assertEquals(new BigDecimal("987654.32"), response.getTotal());
        assertEquals(BigDecimal.ZERO, response.getShippingFee());
        assertEquals("Updated note", response.getNote());
        assertNull(response.getDeliveryAddressSource());
        assertEquals("Snapshot", response.getAddressLine());
    }

    @Test void rejectsAdminFeeChangeEvenWhenSubmittedWithNote() {
        OrderEntity order = existing(true);
        order.setShippingFee(new BigDecimal("7000"));
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest request = new UpdateOrderRequest();
        request.setShippingFee(new BigDecimal("3000")); request.setNote("Updated note");
        assertThrows(IllegalArgumentException.class, () -> service.updateOrder(order.getId(), request));
        assertNull(order.getNote());
        assertEquals(new BigDecimal("7000"), order.getShippingFee());
        assertEquals(new BigDecimal("987654.32"), order.getTotal());
        verify(orders, never()).save(any());
    }

    @Test void completedOrderAllowsOnlyNoteCorrection() {
        OrderEntity order = existing(true);
        order.setStatus("completed"); order.setNote("Old note");
        when(orders.findLockedById(order.getId())).thenReturn(Optional.of(order));
        UpdateOrderRequest noteRequest = new UpdateOrderRequest();
        noteRequest.setStatus("completed"); noteRequest.setNote("Corrected note");
        assertEquals("Corrected note", service.updateOrder(order.getId(), noteRequest).getNote());
        assertEquals("completed", order.getStatus());
        UpdateOrderRequest feeRequest = new UpdateOrderRequest();
        feeRequest.setStatus("completed"); feeRequest.setNote("Other note");
        feeRequest.setShippingFee(new BigDecimal("3000"));
        assertThrows(IllegalArgumentException.class, () -> service.updateOrder(order.getId(), feeRequest));
        assertEquals("Corrected note", order.getNote());
    }

    private OrderEntity existing(boolean snapshot) {
        OrderEntity order = new OrderEntity(); order.setId(UUID.randomUUID()); order.setStatus("processing");
        order.setTotal(new BigDecimal("987654.32")); order.setAddressLine("Snapshot");
        order.setCreatedAt(OffsetDateTime.now().minusYears(1));
        if (snapshot) {
            order.setDeliveryAddressSource("GOGODUK"); order.setDeliveryFormattedAddress("Snapshot");
            order.setDeliveryPlaceId("id"); order.setDeliveryLatitude(BigDecimal.ONE); order.setDeliveryLongitude(BigDecimal.TEN);
        }
        return order;
    }
}
