package com.example.fashion_backend.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModaClientTest {
    @Test void multipartAndWinningImageContractRoundTrip() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search/image", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(request.contains("name=\"image\"")); assertTrue(request.contains("image/png"));
            byte[] body = """
                {"model":"moda","indexVersion":"v","indexedProductCount":1,"items":[
                {"product_id":"p","similarity":0.8,"rank":1,"matchedImage":{
                "imageKey":"key","sourceUrl":"https://cdn.test/a.png","color_id":"blue"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var response = new ModaClient("http://127.0.0.1:" + server.getAddress().getPort(), 3).search(new byte[]{1}, "image/png", "req");
            assertEquals("p", response.items().get(0).productId());
            assertEquals("blue", response.items().get(0).matchedImage().colorId());
        } finally { server.stop(0); }
    }
    @Test void timeoutIsReportedWithoutRawServiceErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var client = new ModaClient("http://127.0.0.1:" + server.getAddress().getPort(), 1);
            assertEquals("AI_TIMEOUT", assertThrows(ImageSearchException.class, () -> client.search(new byte[]{1}, "image/png", "req")).getCode());
        } finally { server.stop(0); }
    }
}
