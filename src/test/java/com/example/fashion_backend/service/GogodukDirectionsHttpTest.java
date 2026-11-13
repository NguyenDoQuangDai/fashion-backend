package com.example.fashion_backend.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GogodukDirectionsHttpTest {
    @Test void sendsDocumentedQueryAndKeyOnlyInHeader() throws Exception {
        var query = new AtomicReference<String>(); var key = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/directions", exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var service = new GogodukService("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 1);
            assertEquals(200, service.directions(ShippingService.WAREHOUSE, "21,105").statusCode());
            assertTrue(query.get().contains("vehicle=motobike"));
            assertTrue(query.get().contains("origin=20.9986611,105.805369"));
            assertTrue(query.get().contains("destination=21,105"));
            assertFalse(query.get().contains("test-key")); assertEquals("test-key", key.get());
        } finally { server.stop(0); }
    }

    @Test void actualHttpTimeoutReturns504() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/directions", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var service = new GogodukService("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 1);
            assertEquals(504, service.directions(ShippingService.WAREHOUSE, "21,105").statusCode());
        } finally { server.stop(0); }
    }
}
