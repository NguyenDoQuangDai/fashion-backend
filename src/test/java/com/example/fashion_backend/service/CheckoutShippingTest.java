package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.*;
import com.example.fashion_backend.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutShippingTest {
    @org.junit.jupiter.api.BeforeEach void bindInventory() { InventoryTestSupport.bind(service, products); }
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final DeliveryAddressService addresses = mock(DeliveryAddressService.class);
    private final ShippingService shipping = mock(ShippingService.class);
    private final MailService mail = mock(MailService.class);
    private final OrderService service = new OrderService(orders, products, mail, addresses, shipping);
    private final ProductSizeVariantEntity size = new ProductSizeVariantEntity();
    private Order request;

    @BeforeEach void setup() {
        when(addresses.resolve(any())).thenReturn(new DeliveryAddressService.ResolvedAddress("Address", "id", BigDecimal.ONE, BigDecimal.TEN, null));
        when(shipping.quote(any())).thenReturn(new ShippingService.ShippingQuote(new BigDecimal("6000.01"), new BigDecimal("6000")));
        size.setSizeId("M"); size.setInventory(10); size.setPrice(new BigDecimal("125000"));
        var color = new ProductColorVariantEntity(); color.setColorId("black"); color.setSizes(Set.of(size));
        var product = new ProductEntity(); product.setId("p1"); product.setTitle("Test"); product.setColors(List.of(color));
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(i -> { OrderEntity order = i.getArgument(0); order.setId(UUID.randomUUID()); return order; });
        var item = new CartItem("p1", 2); item.setColorId("black"); item.setSizeId("M");
        request = new Order(); request.setItems(List.of(item));
        request.setExpectedShippingFee(new BigDecimal("6000")); request.setExpectedTotal(new BigDecimal("256000"));
    }

    @Test void quoteComputesBackendPricesWithoutSideEffects() {
        var quote = service.quote(request);
        assertEquals(new BigDecimal("250000"), quote.itemsSubtotal());
        assertEquals(new BigDecimal("256000"), quote.total());
        assertEquals(new BigDecimal("6000"), quote.shippingFee());
        assertEquals(10, size.getInventory()); verifyNoInteractions(orders, mail); verify(products, never()).save(any());
    }

    @Test void checkoutRechecksThenPersistsShippingAndGrandTotal() {
        service.quote(request);
        var order = service.createOrder(request, null);
        verify(addresses, times(2)).resolve(any()); verify(shipping, times(2)).quote(any());
        assertEquals(new BigDecimal("6000.01"), order.getShippingDistanceMeters());
        assertEquals(new BigDecimal("6000"), order.getShippingFee());
        assertEquals(new BigDecimal("250000"), order.getItemsSubtotal());
        assertEquals(new BigDecimal("256000"), order.getTotal());
        assertEquals("motobike", order.getShippingVehicle()); assertEquals("GOGODUK", order.getShippingProvider());
        assertNotNull(order.getShippingCalculatedAt()); assertEquals(8, size.getInventory());
        assertEquals("OK", order.getShippingRouteStatus());
        assertEquals(order.getShippingFee(), service.mapToResponse(order).getShippingFee());
    }

    @Test void changedFeeReturnsNewQuoteWithoutSavingOrReducingInventory() {
        request.setExpectedShippingFee(BigDecimal.ZERO);
        var error = assertThrows(ShippingException.class, () -> service.createOrder(request, null));
        assertEquals(409, error.getStatus()); assertEquals(new BigDecimal("256000"), error.getQuote().total());
        assertNoWrites();
    }

    @Test void changedProductPriceRequiresConfirmationEvenWhenShippingMatches() {
        size.setPrice(new BigDecimal("150000"));
        var error = assertThrows(ShippingException.class, () -> service.createOrder(request, null));
        assertEquals(409, error.getStatus()); assertEquals(new BigDecimal("306000"), error.getQuote().total());
        assertNoWrites();
    }

    @Test void missingQuoteDoesNotCreateOrder() {
        request.setExpectedTotal(null);
        assertEquals("QUOTE_REQUIRED", assertThrows(ShippingException.class, () -> service.createOrder(request, null)).getCode());
        assertNoWrites();
    }

    @Test void routeFailureDoesNotChangeStockOrSaveOrder() {
        when(shipping.quote(any())).thenThrow(new ShippingException(422, "SHIPPING_NO_ROAD_ROUTE", "No road route"));
        assertThrows(ShippingException.class, () -> service.createOrder(request, null)); assertNoWrites();
    }

    @Test void invalidItemsCannotProducePartialTotals() {
        request.getItems().get(0).setQuantity(0);
        assertThrows(IllegalArgumentException.class, () -> service.quote(request));
        request.getItems().get(0).setQuantity(2); size.setPrice(null);
        assertThrows(IllegalArgumentException.class, () -> service.quote(request));
        request.getItems().get(0).setProductId("missing");
        assertThrows(IllegalArgumentException.class, () -> service.quote(request)); assertNoWrites();
    }

    @Test void repeatedVariantLinesCannotExceedInventoryTogether() {
        var item = request.getItems().get(0); item.setQuantity(6); request.setItems(List.of(item, item));
        assertThrows(IllegalArgumentException.class, () -> service.quote(request)); assertNoWrites();
    }

    private void assertNoWrites() {
        assertEquals(10, size.getInventory()); verifyNoInteractions(orders, mail); verify(products, never()).save(any());
    }
}
