package com.example.fashion_backend.dto.chat;

import java.util.List;

public class ChatResponse {
    private String reply;
    private List<ChatSuggestedProduct> suggestedProducts;

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public List<ChatSuggestedProduct> getSuggestedProducts() { return suggestedProducts; }
    public void setSuggestedProducts(List<ChatSuggestedProduct> suggestedProducts) { this.suggestedProducts = suggestedProducts; }
}
