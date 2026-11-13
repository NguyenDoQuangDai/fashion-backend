package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable, shared catalog data. Never calls an encoder or a remote service. */
@Component
public class ProductSimilarityIndex {
    private Map<String, Integer> rows = Map.of();
    private double[][] scores;
    private String version;
    private String failure;

    @Autowired
    public ProductSimilarityIndex(@Value("${app.recommendations.moda-index:../fashion-ai/artifacts/recommendations/moda-v1}") String path) {
        this(Path.of(path));
    }

    public ProductSimilarityIndex(Path directory) {
        try {
            var mapper = new ObjectMapper();
            var manifest = mapper.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
            if (manifest.path("formatVersion").asInt() != 1
                    || !manifest.path("modelId").asText().equals("HopitAI/moda-fashion-crossdomain")
                    || !manifest.path("modelRevision").asText().equals("acab047ed01de9bcb6d5bf6136f8668c4becf938")
                    || !manifest.path("similarity").asText().equals("signed_cosine_max_image_pairs")) {
                throw new IllegalArgumentException("Unsupported MODA format/model/semantics");
            }
            byte[] data = Files.readAllBytes(directory.resolve("matrix.json"));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!hash.equals(manifest.path("matrixSha256").asText())) throw new IllegalArgumentException("Matrix checksum mismatch");
            var matrix = mapper.readTree(data);
            var ids = matrix.path("productIds"); var values = matrix.path("scores");
            int count = ids.size();
            if (!ids.isArray() || !values.isArray() || count == 0 || count != manifest.path("productCount").asInt()
                    || values.size() != count) throw new IllegalArgumentException("Matrix dimensions mismatch");
            Map<String, Integer> lookup = new LinkedHashMap<>();
            double[][] loaded = new double[count][count];
            for (int i = 0; i < count; i++) {
                if (!ids.get(i).isTextual() || ids.get(i).asText().isBlank() || lookup.put(ids.get(i).asText(), i) != null)
                    throw new IllegalArgumentException("Invalid/duplicate product ID");
                if (!values.get(i).isArray() || values.get(i).size() != count) throw new IllegalArgumentException("Invalid matrix row");
                for (int j = 0; j < count; j++) {
                    var cell = values.get(i).get(j);
                    double value = cell.asDouble(Double.NaN);
                    if (!cell.isNumber() || !Double.isFinite(value) || value < -1 || value > 1)
                        throw new IllegalArgumentException("Cosine outside [-1,1]");
                    loaded[i][j] = value;
                }
            }
            for (int i = 0; i < count; i++) {
                if (Math.abs(loaded[i][i] - 1) > 1e-5) throw new IllegalArgumentException("Invalid diagonal");
                for (int j = 0; j < count; j++) {
                    if (Math.abs(loaded[i][j] - loaded[j][i]) > 1e-6) throw new IllegalArgumentException("Asymmetric matrix");
                }
            }
            version = manifest.path("indexVersion").asText();
            if (!version.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid index version");
            scores = loaded; rows = Map.copyOf(lookup);
        } catch (Exception error) {
            scores = null; rows = Map.of(); version = null;
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            LoggerFactory.getLogger(ProductSimilarityIndex.class).warn("MODA recommendations unavailable; using Profile/Popularity fallback: {}", failure);
        }
    }

    public boolean isAvailable() { return scores != null; }
    public boolean contains(String id) { return rows.containsKey(id); }
    public Double similarity(String source, String target) {
        Integer i = rows.get(source), j = rows.get(target);
        return i == null || j == null ? null : scores[i][j];
    }
    public String version() { return version; }
    public String failure() { return failure; }
    public int productCount() { return rows.size(); }
}
