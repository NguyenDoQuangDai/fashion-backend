package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Deterministic monetary validation after semantic extraction, independent of incidental numbers. */
final class ChatPricePolicy {
    private static final String MONEY="(\\d{1,3}(?:\\.\\d{3})+|\\d+(?:[,.]\\d+)?)\\s*(trieu|tr|nghin|ngan|k|dong|d)?(?![a-z0-9])";
    private static final Pattern RANGE=Pattern.compile("\\btu\\s+"+MONEY+"\\s+(?:den|toi)\\s+"+MONEY);
    private static final Pattern SINGLE=Pattern.compile("\\b(duoi|thap hon|khong qua|khong vuot qua|toi da|tren|it nhat|toi thieu|khoang|tam|gia dung|gia|ngan sach)\\s+"+MONEY);
    private record Bounds(Long min,Long max,boolean minInclusive,boolean maxInclusive) {}
    static ChatCriteria apply(ChatRequest request,ChatCriteria criteria) {
        if(!criteria.intent().startsWith("product_") || criteria.needsClarification()) return criteria;
        Bounds bounds=read(request.getMessage(),null);
        if(bounds==null) {
            Bounds historical=null;
            for(var turn:request.getHistory()) if(turn.role().equals("user")) historical=read(turn.content(),historical);
            // Some valid full-state outputs use product_search for a refinement. Ground an
            // unchanged interval in user history without requiring that intent label.
            if(criteria.intent().equals("product_refinement") || (historical!=null
                    && Objects.equals(criteria.priceMin(),historical.min()) && Objects.equals(criteria.priceMax(),historical.max())))
                bounds=historical;
        }
        if(bounds==null) {
            if(criteria.priceMin()!=null||criteria.priceMax()!=null)
                throw new IllegalArgumentException("Price not grounded in user conversation");
            return criteria;
        }
        if(bounds.min()!=null&&bounds.max()!=null&&bounds.min()>bounds.max())throw new IllegalArgumentException("Invalid price interval");
        return new ChatCriteria(criteria.intent(),criteria.category(),criteria.productType(),criteria.gender(),
            bounds.min(),bounds.max(),bounds.minInclusive(),bounds.maxInclusive(),criteria.colorsInclude(),criteria.colorsExclude(),
            criteria.size(),criteria.materialPreferences(),criteria.occasion(),criteria.quantity(),criteria.sort(),criteria.needsClarification(),criteria.clarificationQuestion());
    }
    private static Bounds read(String input,Bounds previous) {
        String text=Normalizer.normalize(input,Normalizer.Form.NFD).replaceAll("\\p{M}+","").replace('đ','d').toLowerCase(Locale.ROOT);
        if(text.matches(".*(?:bo|xoa|khong can) (?:gioi han|dieu kien|yeu cau)(?: ve)? (?:gia|ngan sach).*")) return new Bounds(null,null,true,true);
        var range=RANGE.matcher(text);
        if(range.find())return new Bounds(amount(range.group(1),range.group(2)),amount(range.group(3),range.group(4)),true,true);
        var matcher=SINGLE.matcher(text);
        Bounds result=previous;
        int count=0;
        while(matcher.find()) {
            count++; if(count>1)throw new IllegalArgumentException("Ambiguous price expression");
            long value=amount(matcher.group(2),matcher.group(3));
            result=switch(matcher.group(1)) {
                case "duoi","thap hon" -> new Bounds(null,value,true,false);
                case "khong qua","khong vuot qua","toi da" -> new Bounds(null,value,true,true);
                case "tren" -> new Bounds(value,null,false,true);
                case "it nhat","toi thieu" -> new Bounds(value,null,true,true);
                case "khoang","tam" -> new Bounds(Math.max(0,value-50000),value+50000,true,true);
                default -> new Bounds(value,value,true,true);
            };
        }
        return result;
    }
    private static long amount(String number,String suffix) {
        if(number.matches("\\d{1,3}(?:\\.\\d{3})+"))number=number.replace(".","");
        var value=new BigDecimal(number.replace(',','.'));
        if(suffix!=null) value=value.multiply(BigDecimal.valueOf(switch(suffix){case "k","nghin","ngan"->1000;case "trieu","tr"->1000000;default->1;}));
        long amount=value.longValueExact();
        if(amount<0||amount>1_000_000_000L)throw new IllegalArgumentException("Invalid monetary amount");
        return amount;
    }
}
