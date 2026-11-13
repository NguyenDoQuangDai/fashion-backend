package com.example.fashion_backend.controller;

import com.example.fashion_backend.config.GlobalExceptionHandler;
import com.example.fashion_backend.entity.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import com.example.fashion_backend.repository.OrderRepository;
import com.example.fashion_backend.repository.ProductRepository;
import com.example.fashion_backend.service.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CheckoutAddressHttpTest {
    private final GogodukService upstream = mock(GogodukService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final MailService mail = mock(MailService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var service = new OrderService(orders, products, mail, new DeliveryAddressService(upstream), new ShippingService(upstream));
        com.example.fashion_backend.service.InventoryTestSupport.bind(service, products);
        mvc = MockMvcBuilders.standaloneSetup(new CheckoutController(service, mock(PaymentService.class), mock(com.example.fashion_backend.service.CartService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(upstream.resolve(anyString(), anyString(), any())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"result\":{\"address\":\"Địa chỉ chuẩn\",\"lat\":21.0288654,\"lon\":105.7819884}}", null, null));
        when(upstream.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":5000}}]}]}", null, null));
        var size = new ProductSizeVariantEntity(); size.setSizeId("M"); size.setPrice(new BigDecimal("100000")); size.setInventory(5);
        var color = new ProductColorVariantEntity(); color.setColorId("black"); color.setSizes(Set.of(size));
        var product = new ProductEntity(); product.setId("p1"); product.setTitle("Test"); product.setColors(List.of(color));
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0); order.setId(UUID.randomUUID()); return order;
        });
    }

    @Test void acceptsFrontendContractAndReturnsCanonicalSnapshot() throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body("21")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.order.deliveryFormattedAddress").value("Địa chỉ chuẩn"))
                .andExpect(jsonPath("$.order.deliveryPlaceId").value("place-1"))
                .andExpect(jsonPath("$.order.deliveryLatitude").value(21.0288654))
                .andExpect(jsonPath("$.order.deliveryAddressSource").value("GOGODUK"))
                .andExpect(jsonPath("$.order.note").value("Số nhà 12"))
                .andExpect(jsonPath("$.order.fullName").value("Nguyễn Văn A"));
    }

    @Test void rejectsLegacyCheckoutPayloadWithoutCreatingOrder() throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"address\":\"old\",\"city\":\"city\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(upstream, orders, products, mail);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "\"\"", "\"NaN\"", "91", "{}"})
    void rejectsInvalidJsonCoordinates(String latitude) throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body(latitude)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(upstream, orders, products, mail);
    }

    @Test void providerFailureIsActionable503AndNoSideEffects() throws Exception {
        when(upstream.resolve(anyString(), anyString(), any())).thenReturn(new GogodukService.UpstreamResponse(504, "{}", null, null));
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body("21")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(orders, products, mail);
    }

    @Test void quoteEndpointReturnsServerTotalWithoutCreatingAnOrder() throws Exception {
        mvc.perform(post("/api/checkout/quote").contentType(MediaType.APPLICATION_JSON).content(body("21")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemsSubtotal").value(100000))
                .andExpect(jsonPath("$.shippingFee").value(0)).andExpect(jsonPath("$.total").value(100000));
        verifyNoInteractions(orders, mail); verify(products, never()).save(any());
    }

    @Test void changedTotalReturns409WithFreshQuote() throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body("21").replace("\"expectedTotal\":100000", "\"expectedTotal\":1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_QUOTE_CHANGED"))
                .andExpect(jsonPath("$.quote.total").value(100000));
        verifyNoInteractions(orders, mail); verify(products, never()).save(any());
    }

    @Test void estimatedRouteReturnsExplicitQuoteThroughHttp() throws Exception {
        when(upstream.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"status\":\"ESTIMATED\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6000.01}}]}]}", null, null));
        mvc.perform(post("/api/checkout/quote").contentType(MediaType.APPLICATION_JSON).content(body("21")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shippingRouteStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.shippingFee").value(6000)).andExpect(jsonPath("$.total").value(106000));
        verifyNoInteractions(orders, mail); verify(products, never()).save(any());
    }

    @Test void checkoutPersistsEstimatedStatusFeeAndTotal() throws Exception {
        when(upstream.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"status\":\"ESTIMATED\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6000.01}}]}]}", null, null));
        String request = body("21").replace("\"expectedShippingFee\":0", "\"expectedShippingFee\":6000")
                .replace("\"expectedTotal\":100000", "\"expectedTotal\":106000");
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.shippingRouteStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.order.shippingFee").value(6000))
                .andExpect(jsonPath("$.order.total").value(106000));
    }

    @Test void rateLimitCarriesRetryDelayFromProviderBody() throws Exception {
        when(upstream.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(429, "{\"retry_after\":12}", null, null));
        mvc.perform(post("/api/checkout/quote").contentType(MediaType.APPLICATION_JSON).content(body("21")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.retryAfter").value(12));
        verifyNoInteractions(orders, products, mail);
    }

    @Test void checkoutHttpReturnsPersistedFeeAndGrandTotal() throws Exception {
        when(upstream.directions(anyString(), anyString())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6000.01}}]}]}", null, null));
        String request = body("21").replace("\"expectedShippingFee\":0", "\"expectedShippingFee\":6000")
                .replace("\"expectedTotal\":100000", "\"expectedTotal\":106000");
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.itemsSubtotal").value(100000))
                .andExpect(jsonPath("$.order.shippingFee").value(6000)).andExpect(jsonPath("$.order.total").value(106000))
                .andExpect(jsonPath("$.order.shippingVehicle").value("motobike"))
                .andExpect(jsonPath("$.order.shippingCalculatedAt").isNotEmpty());
    }

    private String body(String latitude) {
        return """
            {"name":"Nguyễn Văn A","phone":"0900000000","note":"Số nhà 12","expectedShippingFee":0,"expectedTotal":100000,"items":[{"productId":"p1","colorId":"black","sizeId":"M","quantity":1}],
             "deliveryAddress":{"formattedAddress":"Client address","providerPlaceId":"place-1",
             "latitude":%s,"longitude":105,"sessionToken":"session","source":"GOGODUK","addressComponents":{"city":null}}}
            """.formatted(latitude);
    }
}
