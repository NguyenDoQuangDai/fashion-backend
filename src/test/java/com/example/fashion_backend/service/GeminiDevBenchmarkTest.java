package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.example.fashion_backend.model.Product;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in dev-only benchmark. No rules fallback, database, holdout or production configuration changes. */
@EnabledIfSystemProperty(named="chat.gemini.benchmark", matches="true")
class GeminiDevBenchmarkTest {
    final ObjectMapper json = new ObjectMapper();
    final Path source = Path.of("../reports/chatbot/qwen3-v1");
    final Path out = Path.of("../reports/chatbot/gemini-checkpoint-c");
    final ChatPilotTest tools = new ChatPilotTest();

    @Test void evaluateDevWithDurableTurnsAndResume() throws Exception {
        var environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertEquals("rules", environment.getProperty("app.chat.mode"));
        assertEquals("false", environment.getProperty("app.chat.generate-reply"));
        assertEquals("gemini-3.5-flash-lite", environment.getProperty("app.chat.gemini.model"));
        String key = environment.getProperty("app.chat.gemini.api-key");
        assertNotNull(key); assertFalse(key.isBlank());
        Files.createDirectories(out);
        // An OS lock prevents two resumed runners from issuing the same unfinished turn concurrently.
        try (var channel = java.nio.channels.FileChannel.open(out.resolve("run.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            assertNotNull(lock, "Another benchmark runner owns this checkpoint");
            run(key);
        }
    }

    void run(String key) throws Exception {
        var rows = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(source.resolve("cases.jsonl"))) {
            var row = json.readTree(line);
            if ("dev".equals(row.path("split").asText()) && row.path("fault").isNull()) rows.add(row);
        }
        assertEquals(39, rows.size());
        int totalTurns = rows.stream().mapToInt(r -> { int n=0; for(var t:r.path("turns"))
            if(!t.asText().startsWith("assistant: ")) n++; return n; }).sum();
        assertEquals(46, totalTurns);
        ObjectNode manifest = manifest(rows);
        Path manifestPath = out.resolve("manifest.json");
        if (Files.exists(manifestPath)) {
            var frozen = json.readTree(manifestPath.toFile());
            for(var fields=frozen.path("hashes").fields();fields.hasNext();) {
                var entry=fields.next();String name=entry.getKey();
                if(name.endsWith("GeminiDevBenchmarkTest.java") && !entry.getValue().equals(manifest.path("hashes").path(name))) {
                    var amendment=json.readTree(out.resolve("resume-settings-amendment.json").toFile());
                    assertEquals(entry.getValue().asText(),amendment.path("initialHarnessSha256").asText());
                    assertEquals(manifest.path("hashes").path(name).asText(),amendment.path("fixedHarnessSha256").asText());
                } else assertEquals(entry.getValue(),manifest.path("hashes").path(name),"Frozen benchmark input changed: "+name);
            }
            assertEquals(frozen.path("settings"),json.readTree(manifest.path("settings").toString()));
            manifest = (ObjectNode) frozen;
        } else GeminiBenchmarkTransport.atomic(manifestPath, manifest);
        var prior = new ArrayList<JsonNode>(); manifest.path("priorUsage").forEach(prior::add);
        List<Product> catalog = List.of(json.readValue(source.resolve("catalog.json").toFile(), Product[].class));
        var search = new ChatProductSearchService(null);
        int stopAfter = Integer.getInteger("chat.gemini.benchmark.stop-after", 46);
        assertTrue(stopAfter > 0 && stopAfter <= 46);
        var results = new ArrayList<JsonNode>(); int newlyProcessed = 0, restored = 0;
        try (var wire = new GeminiBenchmarkTransport(out.resolve("http"),
                URI.create("https://generativelanguage.googleapis.com"), prior)) {
            var adapter = new GeminiChatClient(wire.root(), key, "gemini-3.5-flash-lite", 60, 1);
            var calls = new ArrayList<ChatModelClient.Completion>();
            ChatModelClient recording = new ChatModelClient() {
                public Completion complete(String system,String input,JsonNode schema,int tokens) {
                    var c=adapter.complete(system,input,schema,tokens);calls.add(c);return c;
                }
                public Completion complete(String system,String input,JsonNode schema,int tokens,long ms) {
                    var c=adapter.complete(system,input,schema,tokens,ms);calls.add(c);return c;
                }
            };
            var parser = new ChatCriteriaParser(recording);
            var replies = new ChatReplyService(recording, false);
            outer: for(var row : rows) {
                var history = new ArrayList<ChatRequest.Turn>(); int index=0, userTurns=0;
                for(var t:row.path("turns")) if(!t.asText().startsWith("assistant: "))userTurns++;
                for(var turn:row.path("turns")) {
                    if(turn.asText().startsWith("assistant: ")) {
                        history.add(new ChatRequest.Turn("assistant",turn.asText().substring(11)));continue;
                    }
                    index++; var request = new ChatRequest();request.setMessage(turn.asText());
                    request.setHistory(List.copyOf(history.subList(Math.max(0,history.size()-6),history.size())));
                    String id=row.path("id").asText(), turnKey=id+"-turn-"+index;
                    Path checkpoint=out.resolve("turns").resolve(turnKey+".json");
                    if(Files.exists(checkpoint)) {
                        var saved=json.readTree(checkpoint.toFile());
                        assertEquals(json.valueToTree(request),saved.path("input"),"Resume state changed at "+turnKey);
                        history=new ArrayList<>(List.of(json.treeToValue(saved.path("historyAfter"),ChatRequest.Turn[].class)));
                        results.add(saved); restored++;
                    } else {
                        wire.begin(turnKey); wire.prepare(); calls.clear();
                        var result=json.createObjectNode();result.put("id",id);result.put("group",row.path("group").asText());
                        result.put("split","dev");result.put("turn",index);result.put("finalTurn",index==userTurns);
                        result.set("input",json.valueToTree(request));result.put("fallbackUsed",false);
                        result.put("provider","gemini");result.put("model","gemini-3.5-flash-lite");
                        long start=System.nanoTime();
                        try {
                            var parsed=parser.parse(request);var c=parsed.criteria();
                            result.set("actual",json.valueToTree(c));result.set("rawCriteria",parsed.rawCriteria());
                            result.put("reasonCode",parsed.reasonCode());result.put("errorOrigin","none");
                            result.put("retrievalExecuted",!c.needsClarification()&&c.intent().startsWith("product_"));
                            result.put("extractionMs",parsed.completion().elapsedMs());
                            long retrievalStart=System.nanoTime();
                            var matched=result.path("retrievalExecuted").asBoolean()?search.search(c,catalog):List.<ChatProductSearchService.Match>of();
                            result.put("retrievalMs",(System.nanoTime()-retrievalStart)/1e6);
                            result.set("products",json.valueToTree(matched.stream().map(ChatProductSearchService.Match::product).toList()));
                            String reply=matched.isEmpty()?"Bạn muốn điều chỉnh điều kiện nào?":replies.reply(c,matched,60000);
                            result.put("reply",reply);
                            history.add(new ChatRequest.Turn("user",request.getMessage()));
                            history.add(new ChatRequest.Turn("assistant",reply.substring(0,Math.min(1200,reply.length()))));
                            if(index==userTurns) {
                                var expected=row.path("expected");var mismatches=json.createArrayNode();
                                if(expected.path("needsClarification").asBoolean()) {
                                    if(!c.needsClarification())mismatches.add("needsClarification");
                                } else {
                                    for(var fields=expected.fieldNames();fields.hasNext();) {
                                        String f=fields.next();if(f.equals("clarificationQuestion")||f.equals("intent"))continue;
                                        if(!tools.same(expected.get(f),result.path("actual").get(f)))mismatches.add(f);
                                    }
                                }
                                result.set("mismatches",mismatches);result.put("exactConstraints",mismatches.isEmpty());
                                result.put("intentCorrect",expected.path("intent").asText().equals(c.intent()));
                                result.set("hardViolations",tools.violations(expected,matched,catalog));
                            }
                        } catch(ChatModelClient.Unavailable ex) {
                            wire.check();String code=ex.reasonCode();
                            boolean api400=wire.current.stream().anyMatch(e->e.path("status").asInt()==400);
                            if(api400 || Set.of("rate_limited","quota_exhausted","model_timeout","provider_unavailable",
                                    "auth_error","model_not_found","interrupted","busy","provider_http_error").contains(code))
                                throw new IllegalStateException("Benchmark paused: "+code+" at "+turnKey+"; HTTP ledger preserved");
                            result.put("error",code);result.put("reasonCode",code);
                            result.put("errorOrigin",code.equals("grounding_error")?"java_grounding":
                                code.equals("validation_error")?"java_validation":"model_output_or_schema");
                            history.add(new ChatRequest.Turn("user",request.getMessage()));
                        }
                        wire.check();
                        if(!result.has("rawCriteria")&&!calls.isEmpty()) {
                            try {result.set("rawCriteria",json.readTree(calls.get(0).content()));}catch(Exception ignored){}
                        }
                        if(index==userTurns) {
                            result.set("expected",row.path("expected"));
                            var metrics=ChatBenchmarkMetrics.evaluate(row.path("expected"),result.get("actual"));
                            metrics.set("rawExtraction",ChatBenchmarkMetrics.evaluate(row.path("expected"),result.get("rawCriteria")));
                            metrics.set("retrieval",ChatBenchmarkMetrics.retrieval(row.path("expected"),
                                result.has("products")?result.get("products"):json.createArrayNode(),
                                result.has("hardViolations")?result.get("hardViolations"):json.createArrayNode(),
                                result.path("retrievalExecuted").asBoolean()));result.set("metrics2a",metrics);
                        }
                        result.put("elapsedMs",(System.nanoTime()-start)/1e6);result.set("inference",json.valueToTree(calls));
                        result.set("httpAttempts",json.valueToTree(wire.current));
                        if(!wire.current.isEmpty())result.put("modelVersion",wire.current.get(wire.current.size()-1).path("modelVersion").asText());
                        result.set("historyAfter",json.valueToTree(history));
                        GeminiBenchmarkTransport.atomic(checkpoint,result);results.add(result);newlyProcessed++;
                        System.out.println("GEMINI_DEV "+turnKey+" reason="+result.path("reasonCode").asText()+" ms="+result.path("elapsedMs").asInt());
                    }
                    exportResults(results);
                    if(results.size()>=stopAfter)break outer;
                }
            }
            var status=json.createObjectNode();status.put("complete",results.size()==46);status.put("turns",results.size());
            status.put("finalCases",results.stream().filter(r->r.path("finalTurn").asBoolean()).count());
            status.put("restoredTurns",restored);status.put("newTurnsThisInvocation",newlyProcessed);
            status.put("httpAttemptsIncludingPriorSmoke",wire.ledger().size());status.put("updatedAt",Instant.now().toString());
            GeminiBenchmarkTransport.atomic(out.resolve("status.json"),status);
            assertEquals(stopAfter,results.size());
        }
    }

    void exportResults(List<JsonNode> rows) throws Exception {
        Path temp=out.resolve("results.jsonl.tmp");var lines=new ArrayList<String>();
        for(var r:rows)lines.add(json.writeValueAsString(r));Files.write(temp,lines);
        Files.move(temp,out.resolve("results.jsonl"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }

    ObjectNode manifest(List<JsonNode> rows) throws Exception {
        var m=json.createObjectNode();m.put("createdAt",Instant.now().toString());m.put("split","dev");
        var settings=m.putObject("settings");settings.put("model","gemini-3.5-flash-lite");settings.put("generateReply",false);
        settings.put("temperature",0.7);settings.put("topP",0.8);settings.put("topK",20);settings.put("seed",42);
        settings.put("thinkingLevel","LOW");settings.put("maxOutputTokens",512);settings.put("timeoutSeconds",60);
        settings.put("maxRetries",1);settings.put("rpmCap",GeminiBenchmarkTransport.RPM);settings.put("tpmCap",GeminiBenchmarkTransport.TPM);
        settings.put("rpdCap",GeminiBenchmarkTransport.RPD);settings.put("minRequestSpacingMs",GeminiBenchmarkTransport.SPACING_MS);
        m.put("diagnosticMetricVersion","2a-v1");m.put("legacyMetricDefinition","unchanged-from-dev-v4");
        var hashes=m.putObject("hashes");
        for(String file:List.of("cases.jsonl","catalog.json","results-dev-2b2-r3c-b2.jsonl","manifest-dev-2b2-r3c-b2.json"))
            hashes.put("qwen3-v1/"+file,tools.sha(source.resolve(file)));
        var baseline=json.readTree(source.resolve("manifest-dev-2b2-r3c-b2.json").toFile());
        for(String file:List.of("ChatCriteriaGrounding.java","ChatProductSearchService.java","ChatPricePolicy.java")) {
            Path path=Path.of("src/main/java/com/example/fashion_backend/service/"+file);
            String expected=null;
            for(var it=baseline.path("hashes").fields();it.hasNext();) {var e=it.next();if(e.getKey().endsWith(file))expected=e.getValue().asText();}
            assertNotNull(expected);assertEquals(expected,tools.sha(path),"Baseline changed: "+file);
        }
        for(String file:List.of("cases.jsonl","catalog.json"))
            assertEquals(baseline.path("hashes").path(file).asText(),tools.sha(source.resolve(file)),"Baseline data changed: "+file);
        assertEquals("a44f2f89fbe41f0c5dd6e5e9af14e75d688ead65cadcc134ab01f7770b5c3b77",tools.sha(Path.of("src/main/resources/chat/extraction.txt")));
        assertEquals("8d7aac71de4624e2afa874fd0a22c30e17e0c3ee92cbd316e6833895d06378dd",tools.sha(Path.of("src/main/resources/chat/criteria.schema.json")));
        try(var files=Files.walk(Path.of("src/main"))) {
            for(var path:files.filter(Files::isRegularFile).filter(p->p.toString().contains("Chat")||p.toString().contains("chat\\")).sorted().toList())
                hashes.put(path.toString(),tools.sha(path));
        }
        for(String f:List.of("GeminiDevBenchmarkTest.java","GeminiBenchmarkTransport.java","ChatPilotTest.java","ChatBenchmarkMetrics.java")) {
            var p=Path.of("src/test/java/com/example/fashion_backend/service/"+f);hashes.put(p.toString(),tools.sha(p));
        }
        hashes.put("src/main/resources/application.properties",tools.sha(Path.of("src/main/resources/application.properties")));
        var prior=m.putArray("priorUsage");
        for(String f:List.of("gemini-checkpoint-b-smoke.jsonl","gemini-checkpoint-b-smoke-3.5-flash-lite.jsonl")) {
            Path p=source.getParent().resolve(f);hashes.put(f,tools.sha(p));
            for(String line:Files.readAllLines(p)) {
                var old=json.readTree(line);var event=prior.addObject();event.put("startedAt",old.path("time").asText());
                event.put("source",f);event.put("model",old.path("model").asText());
                event.set("usageMetadata",old.path("usageMetadata"));
            }
        }
        m.set("devCaseIds",json.valueToTree(rows.stream().map(r->r.path("id").asText()).toList()));
        return m;
    }
}
