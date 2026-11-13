package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductSimilarityIndexTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    void bundle(String matrix) throws Exception {
        Files.writeString(directory.resolve("matrix.json"),matrix);
        mapper.writeValue(directory.resolve("manifest.json").toFile(),Map.of(
                "formatVersion",1,"modelId","HopitAI/moda-fashion-crossdomain",
                "modelRevision","acab047ed01de9bcb6d5bf6136f8668c4becf938",
                "similarity","signed_cosine_max_image_pairs","indexVersion","a".repeat(64),"productCount",2,
                "matrixSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("matrix.json"))))));
    }
    @Test void validMatrixPreservesSignedScoresAndMissingIdIsNotZero() throws Exception {
        bundle("{\"productIds\":[\"a\",\"b\"],\"scores\":[[1,-0.3],[-0.3,1]]}");
        var index=new ProductSimilarityIndex(directory);
        assertTrue(index.isAvailable()); assertEquals(-.3,index.similarity("a","b"));
        assertNull(index.similarity("a","missing")); assertFalse(index.contains("missing"));
    }
    @Test void tamperingAndMissingFilesFallBackWithoutPartiallyLoadedData() throws Exception {
        bundle("{\"productIds\":[\"a\",\"b\"],\"scores\":[[1,0],[0,1]]}");
        Files.writeString(directory.resolve("matrix.json"),"{}");
        var index=new ProductSimilarityIndex(directory); assertFalse(index.isAvailable()); assertFalse(index.contains("a"));
        assertFalse(new ProductSimilarityIndex(directory.resolve("missing")).isAvailable());
    }
    @Test void malformedShapeIdsRangeSymmetryAndDiagonalAreRejected() throws Exception {
        for(String matrix:List.of(
                "{\"productIds\":[\"a\",\"a\"],\"scores\":[[1,0],[0,1]]}",
                "{\"productIds\":[\"a\",\"b\"],\"scores\":[[1],[0,1]]}",
                "{\"productIds\":[\"a\",\"b\"],\"scores\":[[1,2],[2,1]]}",
                "{\"productIds\":[\"a\",\"b\"],\"scores\":[[1,0.2],[0.3,1]]}",
                "{\"productIds\":[\"a\",\"b\"],\"scores\":[[0.8,0],[0,1]]}")) {
            bundle(matrix); assertFalse(new ProductSimilarityIndex(directory).isAvailable(),matrix);
        }
    }
    @Test void differentModelSemanticsCannotSilentlyLoad() throws Exception {
        bundle("{\"productIds\":[\"a\",\"b\"],\"scores\":[[1,0],[0,1]]}");
        var path=directory.resolve("manifest.json");
        Files.writeString(path,Files.readString(path).replace("signed_cosine_max_image_pairs","clamped_positive_cosine"));
        assertFalse(new ProductSimilarityIndex(directory).isAvailable());
    }
}
