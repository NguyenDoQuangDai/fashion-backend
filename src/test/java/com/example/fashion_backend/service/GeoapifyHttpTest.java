package com.example.fashion_backend.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeoapifyHttpTest {
    @Test void sendsScooterWaypointsAndReadsTotalMetersWithoutSummingLegsTwice() throws Exception {
        AtomicReference<String> query=new AtomicReference<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/routing",exchange->{
            query.set(java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] body="{\"features\":[{\"properties\":{\"distance\":6000.01,\"distance_units\":\"meters\",\"legs\":[{\"distance\":6000.01}]}}]}".getBytes();
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            FallbackStore state=mock(FallbackStore.class);when(state.acquire(anyString(),anyInt(),anyInt())).thenReturn(true);
            var service=new GeoapifyService("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",1,"scooter",state,new SimpleMeterRegistry());
            var result=service.route(new DeliveryAddressService.ResolvedAddress("A","id",new BigDecimal("21"),new BigDecimal("105"),null));
            assertEquals(new BigDecimal("6000.01"),result.distanceMeters());assertEquals(new BigDecimal("6000"),result.shippingFee());
            assertEquals("GEOAPIFY",result.provider());assertEquals("scooter",result.vehicle());assertEquals("OK",result.routeStatus());
            assertTrue(query.get().contains("waypoints=20.9986611,105.805369|21,105"));assertTrue(query.get().contains("mode=scooter"));
            assertTrue(query.get().contains("units=metric"));
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings={"null","-1","\"NaN\"","1000000001","{}"})
    void rejectsInvalidRouteDistance(String value) throws Exception {
        String body="{\"features\":[{\"properties\":{\"distance\":"+value+",\"distance_units\":\"meters\"}}]}";
        withServer(200,body,service->{
            var error=assertThrows(ShippingException.class,()->service.route(destination()));
            assertEquals(502,error.getStatus());
        });
    }
    @Test void refusesMilesRatherThanSilentlyUndercharging() throws Exception {
        withServer(200,"{\"features\":[{\"properties\":{\"distance\":6,\"distance_units\":\"miles\"}}]}",service->
            assertEquals(502,assertThrows(ShippingException.class,()->service.route(destination())).getStatus()));
    }
    @Test void rateLimitIsSafeAndCarriesRetryAfter() throws Exception {
        withServer(429,"apiKey=test-key",service->{
            var error=assertThrows(ShippingException.class,()->service.route(destination()));
            assertEquals("SHIPPING_RATE_LIMITED",error.getCode());assertEquals(12,error.getRetryAfter());
            assertFalse(error.toString().contains("test-key"));assertNull(error.getCause());
        });
    }
    @Test void upstreamErrorsDoNotLeakKeyOrRawBody() throws Exception {
        withServer(403,"apiKey=test-key",service->{
            var error=assertThrows(AddressException.class,()->service.suggest("Tràng Tiền"));
            assertFalse(error.toString().contains("test-key"));assertNull(error.getCause());
        });
    }
    @Test void refusesMissingKeyBeforeNetworkOrQuotaUse() {
        FallbackStore state=mock(FallbackStore.class);
        var service=new GeoapifyService("https://api.geoapify.com","",1,"scooter",state,new SimpleMeterRegistry());
        assertEquals("ADDRESS_PROVIDER_NOT_CONFIGURED",assertThrows(AddressException.class,()->service.suggest("abc")).getCode());
        verifyNoInteractions(state);
    }
    @Test void noFeaturesMeansNoRoute() throws Exception {
        withServer(200,"{\"features\":[]}",service->assertEquals(422,assertThrows(ShippingException.class,()->service.route(destination())).getStatus()));
    }
    @Test void timeoutDoesNotLeakRequestUrl() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try { Thread.sleep(1400); } catch(InterruptedException ignored){Thread.currentThread().interrupt();} exchange.close();});
        server.start();
        try {
            FallbackStore state=mock(FallbackStore.class);when(state.acquire(anyString(),anyInt(),anyInt())).thenReturn(true);
            var service=new GeoapifyService("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",1,"scooter",state,new SimpleMeterRegistry());
            var error=assertThrows(AddressException.class,()->service.suggest("abc"));
            assertEquals(504,error.getStatus());assertNull(error.getCause());assertFalse(error.toString().contains("test-key"));
        } finally {server.stop(0);}
    }
    private DeliveryAddressService.ResolvedAddress destination() { return new DeliveryAddressService.ResolvedAddress("A","id",BigDecimal.ONE,BigDecimal.ONE,null); }
    private void withServer(int status,String body,java.util.function.Consumer<GeoapifyService> action) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After","12");exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            FallbackStore state=mock(FallbackStore.class);when(state.acquire(anyString(),anyInt(),anyInt())).thenReturn(true);
            action.accept(new GeoapifyService("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",1,"scooter",state,new SimpleMeterRegistry()));
        } finally {server.stop(0);}
    }
}
