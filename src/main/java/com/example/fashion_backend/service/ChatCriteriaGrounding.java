package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import java.util.*;
import java.util.regex.*;

/** Reject invented hard filters. Qwen still determines intent, negation and conversation state. */
final class ChatCriteriaGrounding {
    // Deliberately closed: each phrase names one product type in the current taxonomy.
    private static final Pattern AO_PHONG = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}])(?:áo|ao)\\s+(?:phông|phong)(?![\\p{L}\\p{N}]|\\s+(?:cách|cach)\\b)");
    private static final Pattern QUAN_THE_THAO = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}])(?:quần|quan)\\s+(?:thể\\s+thao|the\\s+thao)(?![\\p{L}\\p{N}])");
    record AliasEvidence(String category, String productType, int start, int end, String span) {}
    private static final Map<String,List<String>> ALIASES=Map.of(
        "thun",List.of("thun","phong","t shirt","tshirt"),
        "short",List.of("short","sooc","soc"),
        "jeans",List.of("jeans","jean"),
        "polo",List.of("polo","poloo"),
        "nam",List.of("nam","bo","ba","chong"),
        "nu",List.of("nu","me","vo"));
    static ChatCriteria validate(ChatRequest request,ChatCriteria c) {
        if(c.needsClarification()||!c.intent().startsWith("product_"))return c;
        var source=new StringBuilder(request.getMessage());
        for(var turn:request.getHistory())if(turn.role().equals("user"))source.append(" ").append(turn.content());
        String text=source.toString();
        String current=ChatText.normalize(request.getMessage());
        if(current.matches(".*\\bsku\\s*[a-z0-9]+\\b.*") || current.matches(".*\\bma(?: san pham)? [a-z]*[0-9][a-z0-9]*\\b.*"))
            throw new IllegalArgumentException("Product code requires clarification");
        c=canonicalizeCurrentAlias(request.getMessage(),c);
        for(String value:Arrays.asList(c.productType(),c.gender(),c.size())) {
            if(value!=null&&!ALIASES.getOrDefault(value,List.of(value)).stream().anyMatch(alias->ChatText.contains(text,alias)))
                throw new IllegalArgumentException("Ungrounded hard criterion");
        }
        // A mentioned specific type must not silently disappear into a broad category search.
        // Unclear/negative type expressions ask again instead of returning unrelated products.
        if(c.productType()==null) {
            for(String type:List.of("polo","thun","so mi","khoac","short","kaki","jeans","the thao","len","ni","phao","giu nhiet","chong nang","ba lo","boxer","brief","tat","chan vay"))
                if(ChatText.contains(request.getMessage(),type))throw new IllegalArgumentException("Specific type was omitted");
        }
        return c;
    }

    private static ChatCriteria canonicalizeCurrentAlias(String message,ChatCriteria c) {
        AliasEvidence evidence=aliasEvidence(message);
        if(evidence==null || c.category()!=null&&!c.category().equals(evidence.category())
                || c.productType()!=null&&!c.productType().equals(evidence.productType())) return c;
        if(c.category()!=null&&c.productType()!=null)return c;
        return new ChatCriteria(c.intent(),c.category()==null?evidence.category():c.category(),
            c.productType()==null?evidence.productType():c.productType(),c.gender(),c.priceMin(),c.priceMax(),
            c.priceMinInclusive(),c.priceMaxInclusive(),c.colorsInclude(),c.colorsExclude(),c.size(),
            c.materialPreferences(),c.occasion(),c.quantity(),c.sort(),c.needsClarification(),c.clarificationQuestion());
    }

    // Package-private so contract tests can assert the exact USER span. History is intentionally
    // untouched here: state inheritance remains Qwen's existing responsibility.
    static AliasEvidence aliasEvidence(String message) {
        AliasEvidence evidence=null;
        for(var rule:List.of(new Object[]{AO_PHONG,"ao","thun"},
                             new Object[]{QUAN_THE_THAO,"quan","the thao"})) {
            Matcher matches=((Pattern)rule[0]).matcher(message);
            while(matches.find()) {
                if(negated(message,matches.start()))continue;
                var found=new AliasEvidence((String)rule[1],(String)rule[2],matches.start(),matches.end(),matches.group());
                if(evidence!=null && (!evidence.category().equals(found.category())
                        || !evidence.productType().equals(found.productType())))return null;
                evidence=found;
            }
        }
        return evidence!=null&&competingProductMention(message,evidence)?null:evidence;
    }

    private static boolean competingProductMention(String message,AliasEvidence evidence) {
        String normalized=ChatText.normalize(message);
        for(String phrase:List.of("ao polo","ao thun","ao so mi","ao khoac","ao len",
                "quan kaki","quan jeans","quan jean","quan short","quan the thao")) {
            if(!ChatText.contains(normalized,phrase))continue;
            String type=phrase.substring(phrase.indexOf(' ')+1);
            if(type.equals("jean"))type="jeans";
            if(!type.equals(evidence.productType()))return true;
        }
        // A second product group joined to the alias makes the requested group ambiguous.
        Matcher joined=Pattern.compile("(?:^| )(?:va|hoac|lan) (ao|quan|vay|bo|phu kien)(?: |$)").matcher(normalized);
        while(joined.find())if(!joined.group(1).equals(evidence.category()))return true;
        return false;
    }

    private static boolean negated(String message,int start) {
        String before=ChatText.normalize(message.substring(Math.max(0,start-35),start));
        return before.matches(".*(?:^| )(?:khong|ko|tranh|dung|bo)(?: muon| lay| can| chon| phai| thich)?");
    }
}
