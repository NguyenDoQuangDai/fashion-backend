package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeminiBenchmarkTransportTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();

    @Test void receivedResponseCanResumeWithoutReissuingAnApiCallOrLosingUsage() throws Exception {
        var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1beta/models/", exchange->{
            count.incrementAndGet();
            byte[] body="{\"modelVersion\":\"fixture-model\",\"usageMetadata\":{\"promptTokenCount\":31},\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}".getBytes();
            exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        URI upstream=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try {
            for(int resume=0;resume<2;resume++) {
                try(var wire=new GeminiBenchmarkTransport(temp,upstream,List.of())) {
                    wire.begin("basic-01-turn-1");
                    var client=new GeminiChatClient(wire.root(),"synthetic-test-key","fixture-model",2,0);
                    assertEquals("{}",client.complete("system","input",json.createObjectNode(),512).content());
                    wire.check();assertEquals(1,wire.ledger().size());
                    assertEquals(31,wire.current.get(0).path("usageMetadata").path("promptTokenCount").asInt());
                    assertEquals(resume==1,wire.current.get(0).path("replayedFromDisk").asBoolean());
                    assertFalse(Files.readString(temp.resolve("basic-01-turn-1-1.json")).contains("synthetic-test-key"));
                }
            }
            assertEquals(1,count.get());
        } finally {server.stop(0);}
    }

    @Test void rateBudgetIncludesPriorRequestsRetriesActualTokensAndUnknownUsage() {
        long now=Instant.parse("2026-10-24T06:00:00Z").toEpochMilli();
        var events=new ArrayList<JsonNode>();
        assertEquals(0,GeminiBenchmarkTransport.waitRequired(events,now));
        for(int i=0;i<10;i++)events.add(event(now-50_000+i*1000,100));
        assertTrue(GeminiBenchmarkTransport.waitRequired(events,now)>0);
        events.clear();events.add(event(now-1000,2400));
        assertEquals(5500,GeminiBenchmarkTransport.waitRequired(events,now));
        events.clear();for(int i=0;i<3;i++)events.add(event(now-20_000,null));
        assertTrue(GeminiBenchmarkTransport.waitRequired(events,now)>0);
        events.clear();events.add(event(now-20_000,1000));
        assertEquals(0,GeminiBenchmarkTransport.waitRequired(events,now));
        for(int i=1;i<400;i++)events.add(event(now-100_000,1));
        assertThrows(IllegalStateException.class,()->GeminiBenchmarkTransport.waitRequired(events,now));
    }

    @Test void checkpointAndRequestHashesAreStableAcrossObjectKeyOrder() throws Exception {
        var left=json.readTree("{\"z\":1,\"a\":{\"b\":2,\"a\":3}}");
        var right=json.readTree("{\"a\":{\"a\":3,\"b\":2},\"z\":1}");
        assertEquals(GeminiBenchmarkTransport.canonical(left),GeminiBenchmarkTransport.canonical(right));
        Path path=temp.resolve("turns/one.json");GeminiBenchmarkTransport.atomic(path,left);
        assertEquals(left,json.readTree(path.toFile()));
        assertFalse(Files.exists(path.resolveSibling("one.json.tmp")));
    }

    @Test void uncertainAttemptStopsResumeInsteadOfDuplicatingABillableRequest() throws Exception {
        var adapterBody=json.readTree("{\"contents\":[],\"generationConfig\":{}}");
        var pending=json.createObjectNode();pending.put("completed",false);
        pending.put("requestSha256",GeminiBenchmarkTransport.sha(json.writeValueAsBytes(GeminiBenchmarkTransport.canonical(adapterBody))));
        pending.put("startedAt",Instant.now().minusSeconds(100).toString());
        GeminiBenchmarkTransport.atomic(temp.resolve("case-turn-1-1.json"),pending);
        try(var wire=new GeminiBenchmarkTransport(temp,URI.create("http://127.0.0.1:1"),List.of())) {
            wire.begin("case-turn-1");
            var request=java.net.http.HttpRequest.newBuilder(wire.root().resolve("/v1beta/models/fixture:generateContent"))
                .header("x-goog-api-key","synthetic-test-key")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(adapterBody.toString())).build();
            assertEquals(409,java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            assertTrue(assertThrows(IllegalStateException.class,wire::check).getMessage().contains("Uncertain HTTP attempt"));
            assertEquals(1,wire.ledger().size());
        }
    }

    JsonNode event(long at,Integer tokens) {
        var e=json.createObjectNode();e.put("startedAt",Instant.ofEpochMilli(at).toString());
        if(tokens!=null)e.putObject("usageMetadata").put("promptTokenCount",tokens);return e;
    }
}
