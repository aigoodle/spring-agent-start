package io.github.aigoodle.knowledge.index;

import io.github.aigoodle.common.util.JsonUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free vector store backed by a plain relational table, so embeddings
 * live in the same database as everything else (the pgvector extension is not
 * required). Vectors are stored as a comma-joined float string and cosine similarity
 * is computed in Java over the dataset's rows. Each instance is scoped to one dataset,
 * matching {@link VectorStoreManager}'s per-dataset model.
 * <p>
 * Suitable for small/medium datasets; swap in a native pgvector/Elasticsearch store
 * via a custom {@link VectorStoreFactory} for large-scale workloads.
 */
public class JdbcVectorStore implements VectorStore {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final String datasetId;
    private final String table;

    public JdbcVectorStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel, String datasetId, String table) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.datasetId = datasetId;
        this.table = table;
    }

    @Override
    public void add(List<Document> documents) {
        for (Document doc : documents) {
            float[] embedding = embeddingModel.embed(doc.getText());
            jdbc.update("INSERT INTO " + table + " (id, dataset_id, content, metadata_json, embedding) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    doc.getId(), datasetId, doc.getText(),
                    JsonUtils.toJson(doc.getMetadata()), encode(embedding));
        }
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", idList.stream().map(x -> "?").toList());
        jdbc.update("DELETE FROM " + table + " WHERE id IN (" + placeholders + ")", idList.toArray());
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("Filter-expression delete is not supported by JdbcVectorStore");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        float[] query = embeddingModel.embed(request.getQuery());
        List<Document> scored = new ArrayList<>();
        jdbc.query("SELECT id, content, metadata_json, embedding FROM " + table + " WHERE dataset_id = ?",
                rs -> {
                    float[] vec = decode(rs.getString("embedding"));
                    double score = cosine(query, vec);
                    if (score >= request.getSimilarityThreshold()) {
                        Map<String, Object> metadata = JsonUtils.parseMap(rs.getString("metadata_json"));
                        scored.add(Document.builder()
                                .id(rs.getString("id"))
                                .text(rs.getString("content"))
                                .metadata(metadata)
                                .score(score)
                                .build());
                    }
                }, datasetId);
        scored.sort(Comparator.comparingDouble(d -> -d.getScore()));
        return scored.size() > request.getTopK() ? scored.subList(0, request.getTopK()) : scored;
    }

    // ------------------------------------------------------------- helpers

    private static String encode(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    private static float[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length != a.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
