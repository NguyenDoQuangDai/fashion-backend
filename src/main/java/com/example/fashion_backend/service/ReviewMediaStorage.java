package com.example.fashion_backend.service;

import java.io.IOException;
import java.io.InputStream;

public interface ReviewMediaStorage {
    StoredReviewMedia store(InputStream input, String originalFilename, String mimeType, long size) throws IOException;
    InputStream open(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;

    record StoredReviewMedia(String storageKey, String mediaUrl, String mimeType, String checksum) { }
}
