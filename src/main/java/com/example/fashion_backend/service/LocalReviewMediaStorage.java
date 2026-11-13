package com.example.fashion_backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalReviewMediaStorage implements ReviewMediaStorage {
    private final Path root;
    private final String publicBaseUrl;

    public LocalReviewMediaStorage(
            @Value("${app.review.upload-dir:./uploads/reviews}") String uploadDir,
            @Value("${app.review.public-base-url:/api/review-images}") String publicBaseUrl) throws IOException {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        Files.createDirectories(root);
    }

    @Override
    public StoredReviewMedia store(InputStream input, String originalFilename, String mimeType, long size) throws IOException {
        String extension = extensionFor(originalFilename, mimeType);
        String key = UUID.randomUUID() + extension;
        Path target = resolveKey(key);
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredReviewMedia(key, publicBaseUrl + "/" + key, mimeType, checksum(target));
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        return Files.newInputStream(resolveKey(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveKey(storageKey));
    }

    private Path resolveKey(String key) throws IOException {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IOException("Invalid review media key");
        }
        return resolved;
    }

    private String extensionFor(String originalFilename, String mimeType) throws IOException {
        if ("image/jpeg".equals(mimeType)) return ".jpg";
        if ("image/png".equals(mimeType)) return ".png";
        if ("image/webp".equals(mimeType)) return ".webp";
        throw new IOException("Media extension does not match validated MIME type");
    }

    private String checksum(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("Checksum algorithm unavailable", ex);
        }
    }
}
