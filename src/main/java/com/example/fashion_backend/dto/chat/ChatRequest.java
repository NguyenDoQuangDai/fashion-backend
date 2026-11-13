package com.example.fashion_backend.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public class ChatRequest {
    @NotBlank
    @Size(max = 1200)
    private String message;

    @Size(max = 6)
    @Valid
    private List<@NotNull Turn> history = List.of();

    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 1200) String content) {}

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<Turn> getHistory() { return history == null ? List.of() : history; }
    public void setHistory(List<Turn> history) { this.history = history; }

    public void validateInput() {
        if (message == null || message.isBlank() || message.length() > 1200 || getHistory().size() > 6)
            throw new IllegalArgumentException("Tin nhắn không hợp lệ hoặc quá dài.");
        int total = message.length();
        for (Turn turn : getHistory()) {
            if (turn == null || !("user".equals(turn.role()) || "assistant".equals(turn.role()))
                    || turn.content() == null || turn.content().isBlank() || turn.content().length() > 1200)
                throw new IllegalArgumentException("Lịch sử hội thoại không hợp lệ.");
            total += turn.content().length();
        }
        if (total > 4800) throw new IllegalArgumentException("Lịch sử hội thoại quá dài.");
    }
}
