package com.example.fashion_backend.service;

import java.text.Normalizer;
import java.util.Locale;

final class ChatText {
    private ChatText() {}
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
    static boolean contains(String source, String phrase) {
        return (" " + normalize(source) + " ").contains(" " + normalize(phrase) + " ");
    }
}
