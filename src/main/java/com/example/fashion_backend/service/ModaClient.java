package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.search.ImageSearchDtos.AiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModaClient {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public ModaClient(@Value("${app.image-search.ai-url:http://127.0.0.1:8091}") String url,
                      @Value("${app.image-search.timeout-seconds:30}") int seconds) {
        endpoint = URI.create(url.replaceAll("/+$", "") + "/v1/search/image");
        timeout = Duration.ofSeconds(seconds);
    }

    public AiResponse search(byte[] image, String contentType, String requestId) {
        String boundary = "moda-" + UUID.randomUUID();
        try {
            var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"query\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(image);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Request-ID", requestId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 400 || response.statusCode() == 415 || response.statusCode() == 422)
                throw new ImageSearchException(400, "INVALID_IMAGE", "Ảnh không hợp lệ. Vui lòng chọn ảnh khác.");
            if (response.statusCode() == 413)
                throw new ImageSearchException(413, "IMAGE_TOO_LARGE", "Ảnh vượt quá 5 MB.");
            if (response.statusCode() != 200)
                throw new ImageSearchException(503, "AI_UNAVAILABLE", "Dịch vụ tìm ảnh đang bận hoặc chưa sẵn sàng. Vui lòng thử lại.");
            try {
                return json.readValue(response.body(), AiResponse.class);
            } catch (IOException ex) {
                throw new ImageSearchException(502, "AI_INVALID_RESPONSE", "Dịch vụ tìm ảnh trả về dữ liệu không hợp lệ.");
            }
        } catch (HttpTimeoutException ex) {
            throw new ImageSearchException(504, "AI_TIMEOUT", "Tìm kiếm bằng ảnh mất quá nhiều thời gian. Vui lòng thử lại.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ImageSearchException(503, "AI_UNAVAILABLE", "Tìm kiếm bằng ảnh đã bị gián đoạn.");
        } catch (IOException ex) {
            throw new ImageSearchException(503, "AI_UNAVAILABLE", "Không thể kết nối dịch vụ tìm ảnh. Vui lòng thử lại.");
        }
    }
}
