package com.example.fashion_backend.controller;

import com.example.fashion_backend.config.GlobalExceptionHandler;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.*;
import com.example.fashion_backend.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GeoapifyCheckoutHttpTest {
    private final GogodukService primary=mock(GogodukService.class);
    private final GeoapifyService geo=mock(GeoapifyService.class);
    private final OrderRepository orders=mock(OrderRepository.class);
    private final ProductRepository products=mock(ProductRepository.class);
    private final MailService mail=mock(MailService.class);
    private final MemoryFallbackStore store=new MemoryFallbackStore();
    private final ObjectMapper mapper=new ObjectMapper();
    private MockMvc mvc;
    private String token,id;
    @BeforeEach void setup() throws Exception {
        when(primary.suggest(anyString(),anyString(),anyString(),anyString())).thenReturn(new GogodukService.UpstreamResponse(503,"{}",null,null));
        when(primary.directions(anyString(),anyString())).thenReturn(new GogodukService.UpstreamResponse(503,"{}",null,null));
        when(geo.mode()).thenReturn("scooter");
        when(geo.suggest(anyString())).thenReturn(mapper.readTree("{\"results\":[{\"formatted\":\"Tràng Tiền\",\"country_code\":\"vn\",\"result_type\":\"street\",\"lat\":21.0245,\"lon\":105.855}]}"));
        when(geo.route(any())).thenReturn(new ShippingService.ShippingQuote(new BigDecimal("6000.01"),new BigDecimal("6000"),"OK","GEOAPIFY","scooter"));
        var modes=new ProviderFallback("geoapify-first","geoapify-first");var lookup=new AddressLookupService(primary,geo,modes,store);
        var service=new OrderService(orders,products,mail,new DeliveryAddressService(primary,lookup),new ShippingService(primary,geo,modes,store));
        com.example.fashion_backend.service.InventoryTestSupport.bind(service, products);
        mvc=MockMvcBuilders.standaloneSetup(new AddressController(lookup,store),new CheckoutController(service,mock(PaymentService.class),mock(com.example.fashion_backend.service.CartService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var size=new ProductSizeVariantEntity();size.setSizeId("M");size.setPrice(new BigDecimal("100000"));size.setInventory(5);
        var color=new ProductColorVariantEntity();color.setColorId("black");color.setSizes(Set.of(size));
        var product=new ProductEntity();product.setId("p1");product.setTitle("Test");product.setColors(List.of(color));
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(i->{OrderEntity order=i.getArgument(0);order.setId(UUID.randomUUID());return order;});
        String body=mvc.perform(get("/api/addresses/suggest").param("input","Tràng Tiền").param("sessionToken","session"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var selected=mapper.readTree(body).path("predictions").get(0);token=selected.path("selectionToken").asText();id=selected.path("placeId").asText();
    }
    @Test void quotesThenPersistsCanonicalGeoSnapshotAndRoutingMetadata() throws Exception {
        mvc.perform(post("/api/checkout/quote").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shippingProvider").value("GEOAPIFY"))
                .andExpect(jsonPath("$.shippingVehicle").value("scooter")).andExpect(jsonPath("$.shippingFee").value(6000));
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.deliveryAddressSource").value("GEOAPIFY"))
                .andExpect(jsonPath("$.order.deliveryLatitude").value(21.0245))
                .andExpect(jsonPath("$.order.shippingProvider").value("GEOAPIFY"))
                .andExpect(jsonPath("$.order.shippingVehicle").value("scooter"))
                .andExpect(jsonPath("$.order.total").value(106000));
        verify(geo,times(1)).route(any());
    }
    @Test void invalidSelectionCannotReachPriceStockOrPayment() throws Exception {
        token=UUID.randomUUID().toString();
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ADDRESS_RESELECT_REQUIRED"));
        verifyNoInteractions(products,orders,mail);verify(geo,never()).route(any());
    }
    @Test void quoteChangeHasNoSideEffects() throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body().replace("106000","100000")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_QUOTE_CHANGED"))
                .andExpect(jsonPath("$.quote.shippingProvider").value("GEOAPIFY"));
        verifyNoInteractions(orders,mail);verify(products,never()).save(any());
    }
    @Test void clientRateLimitDoesNotContactProvidersAgain() throws Exception {
        for(int i=0;i<60;i++)store.acquire("address:client:"+GeoapifyService.hash("127.0.0.1"),60,60000);
        mvc.perform(get("/api/addresses/suggest").param("input","different address").param("sessionToken","session"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
        verify(geo,times(1)).suggest(anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void gogodukSnapshotSurvivesResolveOutageWithEitherRouter(boolean routingFallback) throws Exception {
        when(geo.suggest(anyString())).thenThrow(new AddressException(503,"ADDRESS_PROVIDER_UNAVAILABLE","Unavailable"));
        when(primary.suggest(anyString(),anyString(),anyString(),anyString())).thenReturn(
                new GogodukService.UpstreamResponse(200,"{\"predictions\":[{\"placeId\":\"g-id\",\"mainText\":\"Address\"}]}",null,null));
        when(primary.resolve(anyString(),anyString(),any())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"result\":{\"address\":\"Canonical\",\"lat\":21.0245,\"lon\":105.855}}",null,null));
        mvc.perform(get("/api/addresses/suggest").param("input","Other address").param("sessionToken","session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("GOGODUK"));
        String resolved=mvc.perform(get("/api/addresses/resolve").param("id","g-id").param("source","GOGODUK").param("sessionToken","session"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token=mapper.readTree(resolved).path("result").path("selectionToken").asText();id="g-id";
        when(primary.resolve(anyString(),anyString(),any())).thenReturn(new GogodukService.UpstreamResponse(504,"{}",null,null));
        if(routingFallback) enableRoutingFallback();
        String request=body().replace("\"source\":\"GEOAPIFY\"","\"source\":\"GOGODUK\"");
        mvc.perform(post("/api/checkout/quote").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shippingProvider").value(routingFallback?"GOGODUK":"GEOAPIFY"));
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.deliveryAddressSource").value("GOGODUK"))
                .andExpect(jsonPath("$.order.shippingProvider").value(routingFallback?"GOGODUK":"GEOAPIFY"))
                .andExpect(jsonPath("$.order.deliveryLatitude").value(21.0245));
        verify(primary,times(1)).resolve(anyString(),anyString(),any());
    }
    @Test void geoAddressCanUseGogodukRouting() throws Exception {
        enableRoutingFallback();
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.deliveryAddressSource").value("GEOAPIFY"))
                .andExpect(jsonPath("$.order.shippingProvider").value("GOGODUK"))
                .andExpect(jsonPath("$.order.shippingVehicle").value("motobike"));
        verify(primary,never()).resolve(anyString(),anyString(),any());
    }
    @Test void bothRoutersFailWithoutCreatingOrderOrDiscardingSelection() throws Exception {
        when(geo.route(any())).thenThrow(new ShippingException(504,"SHIPPING_TIMEOUT","Timeout"));
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SHIPPING_PROVIDER_UNAVAILABLE"));
        verifyNoInteractions(products,orders,mail);
        mvc.perform(get("/api/addresses/resolve").param("id",id).param("source","GEOAPIFY")
                .param("selectionToken",token).param("sessionToken","session")).andExpect(status().isOk());
    }
    private void enableRoutingFallback() {
        when(geo.route(any())).thenThrow(new ShippingException(504,"SHIPPING_TIMEOUT","Timeout"));
        when(primary.directions(anyString(),anyString())).thenReturn(new GogodukService.UpstreamResponse(200,
                "{\"status\":\"OK\",\"routes\":[{\"legs\":[{\"distance\":{\"value\":6000.01}}]}]}",null,null));
    }
    private String body() {
        return """
            {"name":"Test","phone":"0900000000","paymentMethod":"COD","expectedShippingFee":6000,"expectedTotal":106000,
             "deliveryAddress":{"formattedAddress":"Forged","providerPlaceId":"%s","latitude":0,"longitude":0,
              "source":"GEOAPIFY","selectionToken":"%s","sessionToken":"session"},
             "items":[{"productId":"p1","colorId":"black","sizeId":"M","quantity":1}]}
            """.formatted(id,token);
    }
}
