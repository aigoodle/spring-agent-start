package io.github.aigoodle.knowledge.rerank;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reranker that delegates to an LLM: asks the model to score each candidate for
 * relevance and reorders by the returned scores. Falls back to the hybrid score if the
 * model reply cannot be parsed, so a bad model never breaks retrieval.
 * <p>
 * The model id is taken from {@code RetrievalConfig#rerankModelId} at call time and
 * resolved via {@link ModelService}, so any provider registered in the model module can
 * act as a reranker without extra wiring.
 */
public class ModelReranker implements Reranker {

    public static final String NAME = "model";
    private static final Logger log = LoggerFactory.getLogger(ModelReranker.class);
    private static final Pattern SCORE_LINE = Pattern.compile("\\[?(\\d+)]?\\s*[:=\\-)]\\s*([0-9]*\\.?[0-9]+)");

    private final ModelService modelService;
    private final String defaultModelId;

    public ModelReranker(ModelService modelService, String defaultModelId) {
        this.modelService = modelService;
        this.defaultModelId = defaultModelId;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<RetrievedSegment> rerank(String query, List<RetrievedSegment> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (defaultModelId == null || defaultModelId.isBlank()) {
            return candidates;
        }
        try {
            ChatClient client = modelService.getChatClient(defaultModelId);
            String prompt = buildPrompt(query, candidates);
            String reply = client.prompt()
                    .system("You are a strict relevance scorer. Only output the requested lines.")
                    .user(prompt).call().content();
            double[] scores = parseScores(reply, candidates.size());
            List<RetrievedSegment> rescored = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                RetrievedSegment s = candidates.get(i);
                if (!Double.isNaN(scores[i])) {
                    s.setScore(scores[i]);
                }
                rescored.add(s);
            }
            rescored.sort(Comparator.comparingDouble(RetrievedSegment::getScore).reversed());
            if (topN > 0 && rescored.size() > topN) {
                return rescored.subList(0, topN);
            }
            return rescored;
        } catch (Exception e) {
            log.warn("Model reranker failed, keeping hybrid order: {}", e.getMessage());
            return candidates;
        }
    }

    private static String buildPrompt(String query, List<RetrievedSegment> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query: ").append(query).append("\n\n");
        sb.append("Score each passage from 0.0 (irrelevant) to 1.0 (highly relevant). ")
                .append("Reply with exactly one line per passage in the format `index: score`.\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String content = candidates.get(i).getContent();
            if (content == null) {
                content = "";
            }
            if (content.length() > 600) {
                content = content.substring(0, 600) + "…";
            }
            sb.append('[').append(i).append("] ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    private static double[] parseScores(String reply, int expected) {
        double[] scores = new double[expected];
        for (int i = 0; i < expected; i++) {
            scores[i] = Double.NaN;
        }
        if (reply == null) {
            return scores;
        }
        Matcher m = SCORE_LINE.matcher(reply);
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                double score = Double.parseDouble(m.group(2));
                if (idx >= 0 && idx < expected) {
                    scores[idx] = Math.max(0.0, Math.min(1.0, score));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return scores;
    }
}
