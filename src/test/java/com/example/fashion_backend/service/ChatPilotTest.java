package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.example.fashion_backend.model.Product;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-model pilot; excluded from ordinary builds unless requested. No DB or Gemini. */
@EnabledIfSystemProperty(named="chat.pilot.split", matches="dev|holdout|performance")
class ChatPilotTest {
    final ObjectMapper json = new ObjectMapper();
    final Path out = Path.of("../reports/chatbot/qwen3-v1");
    static class RecordingClient extends QwenChatClient {
        final List<ChatModelClient.Completion> calls = new ArrayList<>();
        RecordingClient() { super("http://127.0.0.1:8082", "fashion-qwen3-4b",60,42); }
        @Override public ChatModelClient.Completion complete(String system,String input,JsonNode schema,int tokens,long remaining) {
            var result=super.complete(system,input,schema,tokens,remaining); calls.add(result); return result;
        }
    }
    @Test void evaluateFrozenCases() throws Exception {
        String split=System.getProperty("chat.pilot.split");
        boolean performance=split.equals("performance");
        String label=System.getProperty("chat.pilot.label",split);
        assertTrue(label.matches("[a-zA-Z0-9_-]+"));
        var client=new RecordingClient(); var parser=new ChatCriteriaParser(client);
        var search=new ChatProductSearchService(null);
        var catalog=List.of(json.readValue(out.resolve("catalog.json").toFile(),Product[].class));
        var replies=new ChatReplyService(client,Boolean.getBoolean("chat.pilot.generate"));
        var legacy=new LegacyChatBaseline(org.mockito.Mockito.mock(ProductService.class),json,"","unused");
        var rows=Files.readAllLines(out.resolve("cases.jsonl")).stream().map(line->{try{return json.readTree(line);}catch(Exception e){throw new IllegalStateException(e);}}).toList();
        Set<String> perfIds=new LinkedHashSet<>(List.of("basic-01","basic-02","basic-03","budget-01","budget-03","budget-04","budget-05","budget-06","constraints-02","constraints-03","constraints-04","constraints-05","language-01","language-02","language-03","conversation-01","conversation-02","conversation-05","catalog-01","catalog-04"));
        if (performance) {
            for(int i=0;i<3;i++){var warm=new ChatRequest();warm.setMessage("Áo polo nam");parser.parse(warm);}
        }
        Path resultPath=out.resolve("results-"+label+".jsonl");
        assertFalse(Files.exists(resultPath),"Use a new label to preserve prior runs");
        var hashes=json.createObjectNode();
        try(var files=Files.walk(Path.of("src/main"))) {
            for(var path:files.filter(Files::isRegularFile).filter(p->p.toString().contains("Chat")||p.toString().contains("chat\\")).toList())
                hashes.put(path.toString(),sha(path));
        }
        for(String name:List.of("cases.jsonl","catalog.json","baseline-ChatService.java.txt"))hashes.put(name,sha(out.resolve(name)));
        for(String name:List.of("ChatPilotTest.java","ChatBenchmarkMetrics.java")) {
            var evaluator=Path.of("src/test/java/com/example/fashion_backend/service/"+name);
            hashes.put(evaluator.toString(),sha(evaluator));
        }
        hashes.put("scripts/summarize_chatbot_metrics.py",sha(Path.of("../scripts/summarize_chatbot_metrics.py")));
        hashes.put("src/main/resources/application.properties",sha(Path.of("src/main/resources/application.properties")));
        var manifest=json.createObjectNode();manifest.put("label",label);manifest.put("split",split);manifest.put("seed",42);
        manifest.put("temperature",0.7);manifest.put("top_p",0.8);manifest.put("top_k",20);manifest.put("generate",Boolean.getBoolean("chat.pilot.generate"));
        manifest.put("diagnosticMetricVersion","2a-v1");manifest.put("legacyMetricDefinition","unchanged-from-dev-v4");
        manifest.put("time",java.time.Instant.now().toString());manifest.set("hashes",hashes);
        manifest.set("runtime",json.readTree(Path.of("../fashion-ai/artifacts/chatbot/runtime-lock.json").toFile()));
        Files.writeString(out.resolve("manifest-"+label+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        int cases=0,turns=0,correct=0;
        for(var row:rows) {
            if(performance?!perfIds.contains(row.path("id").asText()):!split.equals(row.path("split").asText())) continue;
            cases++;
            var history=new ArrayList<ChatRequest.Turn>();
            int userTurns=0;
            for(var turn:row.path("turns")) if(!turn.asText().startsWith("assistant: ")) userTurns++;
            int index=0;
            for(var turn:row.path("turns")) {
                if(turn.asText().startsWith("assistant: ")) { history.add(new ChatRequest.Turn("assistant",turn.asText().substring(11)));continue; }
                index++; var request=new ChatRequest();request.setMessage(turn.asText());
                request.setHistory(List.copyOf(history.subList(Math.max(0,history.size()-6),history.size())));
                var result=json.createObjectNode();result.put("id",row.path("id").asText());result.put("group",row.path("group").asText());result.put("split",row.path("split").asText());
                result.put("turn",index);result.put("finalTurn",index==userTurns);result.set("input",json.valueToTree(request));
                result.set("baseline",baseline(legacy,request.getMessage()));
                client.calls.clear();long start=System.nanoTime();
                try {
                    if(!row.path("fault").isNull()) {
                        result.put("fault",row.path("fault").asText());result.put("testedBy","ChatServiceTest / ChatCriteriaParserTest");
                        result.put("excludedFromNlu",true);
                    } else {
                        var parsed=parser.parse(request);var c=parsed.criteria();
                        result.set("actual",json.valueToTree(c));
                        result.set("rawCriteria",parsed.rawCriteria());result.put("reasonCode",parsed.reasonCode());
                        result.put("retrievalExecuted",!c.needsClarification()&&c.intent().startsWith("product_"));
                        var matched=(c.needsClarification()||!c.intent().startsWith("product_"))?List.<ChatProductSearchService.Match>of():search.search(c,catalog);
                        long retrievalDone=System.nanoTime();
                        result.put("extractionMs",parsed.completion().elapsedMs());
                        result.put("retrievalMs",(retrievalDone-start)/1e6-parsed.completion().elapsedMs());
                        result.set("products",json.valueToTree(matched.stream().map(ChatProductSearchService.Match::product).toList()));
                        String reply=matched.isEmpty()?"Bạn muốn điều chỉnh điều kiện nào?":replies.reply(c,matched,60000);
                        result.put("reply",reply);result.put("generationMs",(System.nanoTime()-retrievalDone)/1e6);
                        history.add(new ChatRequest.Turn("user",request.getMessage()));
                        history.add(new ChatRequest.Turn("assistant",reply.substring(0,Math.min(1200,reply.length()))));
                        if(index==userTurns) {
                            var expected=row.path("expected");var actual=json.valueToTree(c);
                            var mismatches=json.createArrayNode();
                            if(expected.path("needsClarification").asBoolean()) {
                                if(!c.needsClarification())mismatches.add("needsClarification");
                            } else {
                                for(var fields=expected.fieldNames();fields.hasNext();) {
                                    String key=fields.next();if(key.equals("clarificationQuestion")||key.equals("intent"))continue;
                                    if(!same(expected.get(key),actual.get(key)))mismatches.add(key);
                                }
                            }
                            result.set("mismatches",mismatches);result.put("exactConstraints",mismatches.isEmpty());
                            result.put("intentCorrect",expected.path("intent").asText().equals(c.intent()));
                            result.set("expected",expected);
                            result.set("hardViolations",violations(expected,matched,catalog));
                            if(mismatches.isEmpty())correct++;
                        }
                    }
                } catch(Exception ex) {
                    result.put("error",ex.getMessage());
                    result.put("reasonCode",ex instanceof ChatModelClient.Unavailable u ? u.reasonCode() : "benchmark_error");
                    history.add(new ChatRequest.Turn("user",request.getMessage()));
                }
                if(index==userTurns && !result.path("excludedFromNlu").asBoolean()) {
                    var expected=row.path("expected");
                    if(!result.has("rawCriteria") && !client.calls.isEmpty()) {
                        try {result.set("rawCriteria",json.readTree(client.calls.get(0).content()));}
                        catch(Exception ignored) { /* Invalid JSON is already recorded verbatim in inference. */ }
                    }
                    var diagnostic=ChatBenchmarkMetrics.evaluate(expected,result.get("actual"));
                    diagnostic.set("rawExtraction",ChatBenchmarkMetrics.evaluate(expected,result.get("rawCriteria")));
                    diagnostic.set("retrieval",ChatBenchmarkMetrics.retrieval(expected,
                        result.has("products")?result.get("products"):json.createArrayNode(),
                        result.has("hardViolations")?result.get("hardViolations"):json.createArrayNode(),
                        result.path("retrievalExecuted").asBoolean()));
                    result.set("metrics2a",diagnostic);
                }
                result.put("elapsedMs",(System.nanoTime()-start)/1e6);result.set("inference",json.valueToTree(client.calls));
                Files.writeString(resultPath,json.writeValueAsString(result)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                turns++;System.out.println("PILOT "+label+" "+row.path("id").asText()+" turn="+index+" ms="+result.path("elapsedMs").asInt()+" error="+result.path("error").asText("none"));
            }
        }
        System.out.println("PILOT complete cases="+cases+" turns="+turns+" finalConstraintsCorrect="+correct);
        assertEquals(performance?20:split.equals("dev")?40:60,cases);
    }
    String sha(Path path)throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    ArrayNode violations(JsonNode expected,List<ChatProductSearchService.Match> matches,List<Product> catalog) {
        var failures=json.createArrayNode();
        if((expected.path("needsClarification").asBoolean()||!expected.path("intent").asText().startsWith("product_"))&&!matches.isEmpty())failures.add("should_not_return_products");
        for(var m:matches) {
            var card=m.product();var p=catalog.stream().filter(x->x.getId().equals(card.getId())).findFirst().orElseThrow();
            if(expected.path("priceMax").isNumber()) {
                int cmp=card.getPrice().compareTo(expected.path("priceMax").decimalValue());
                if(cmp>0||(cmp==0&&!expected.path("priceMaxInclusive").asBoolean()))failures.add("priceMax:"+card.getId());
            }
            if(expected.path("priceMin").isNumber()) {
                int cmp=card.getPrice().compareTo(expected.path("priceMin").decimalValue());
                if(cmp<0||(cmp==0&&!expected.path("priceMinInclusive").asBoolean()))failures.add("priceMin:"+card.getId());
            }
            if(!expected.path("gender").isNull()&&!ChatText.normalize(p.getGender()).equals(expected.path("gender").asText()))failures.add("gender:"+card.getId());
            if(!expected.path("productType").isNull()&&!ChatText.contains(p.getTitle()+" "+p.getCategoryName(),expected.path("productType").asText()))failures.add("productType:"+card.getId());
            if(!expected.path("size").isNull()&&!expected.path("size").asText().equalsIgnoreCase(card.getMatchedSize()))failures.add("size:"+card.getId());
            for(var color:expected.path("colorsExclude"))if(ChatText.contains(card.getMatchedColor(),color.asText()))failures.add("excludedColor:"+card.getId());
            if(!expected.path("colorsInclude").isEmpty()) {
                boolean ok=false;for(var color:expected.path("colorsInclude"))ok|=ChatText.contains(card.getMatchedColor(),color.asText());
                if(!ok)failures.add("includedColor:"+card.getId());
            }
            boolean stock=p.getColors().stream().filter(x->Objects.equals(x.getColorName(),card.getMatchedColor()))
                .flatMap(x->x.getSizes().stream()).anyMatch(x->Objects.equals(x.getSize(),card.getMatchedSize())&&x.getInventory()>0);
            if(!stock)failures.add("stock:"+card.getId());
        }
        return failures;
    }
    boolean same(JsonNode a,JsonNode b) {
        if(a.isNumber()&&b.isNumber()) return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isArray()&&b.isArray()) {
            var aa=new TreeSet<String>();var bb=new TreeSet<String>();a.forEach(x->aa.add(x.asText()));b.forEach(x->bb.add(x.asText()));return aa.equals(bb);
        }
        return a.equals(b);
    }
    JsonNode baseline(LegacyChatBaseline legacy,String message)throws Exception {
        var result=json.createObjectNode();String text=ChatText.normalize(message);
        for(String[] pair:List.of(new String[]{"isAllowed","allowed"},new String[]{"extractKeyword","productType"},new String[]{"extractGender","gender"},new String[]{"extractCategoryType","category"},new String[]{"extractQuantity","quantity"},new String[]{"extractPrice","priceTarget"})) {
            var method=LegacyChatBaseline.class.getDeclaredMethod(pair[0],String.class);method.setAccessible(true);result.set(pair[1],json.valueToTree(method.invoke(legacy,text)));
        }
        var range=LegacyChatBaseline.class.getDeclaredMethod("buildPriceRange",java.math.BigDecimal.class);range.setAccessible(true);
        var price=result.path("priceTarget").isNumber()?result.path("priceTarget").decimalValue():null;
        result.set("priceRange",json.valueToTree(range.invoke(legacy,price)));return result;
    }
}
