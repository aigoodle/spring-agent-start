package io.github.aigoodle.knowledge.retrieve;

import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.enums.RetrievalMethod;

/** Immutable effective settings for one retrieval request. */
record RetrievalPlan(
        RetrievalMethod method,
        int topK,
        int recallLimit,
        double scoreThreshold,
        double vectorWeight,
        double keywordWeight,
        RetrievalConfig.FusionMethod fusionMethod,
        int rrfK,
        int maxChunksPerDocument) {

    static RetrievalPlan resolve(RetrievalConfig config,
                                 RetrievalRequest request,
                                 boolean vectorIndexAvailable) {
        RetrievalMethod method = request.getMethod() != null ? request.getMethod() : config.getMethod();
        if (method == RetrievalMethod.VECTOR && !vectorIndexAvailable) {
            method = RetrievalMethod.FULL_TEXT;
        }

        int topK = request.getTopK() != null ? request.getTopK() : config.getTopK();
        int recallLimit = config.isRerankEnabled()
                ? Math.max(topK, config.getRerankPoolSize())
                : Math.max(topK * Math.max(1, config.getRecallMultiplier()), topK);
        double vectorWeight = request.getVectorWeight() != null
                ? request.getVectorWeight()
                : config.getVectorWeight();

        return new RetrievalPlan(
                method,
                topK,
                recallLimit,
                request.getScoreThreshold() != null
                        ? request.getScoreThreshold()
                        : config.getScoreThreshold(),
                vectorWeight,
                Math.max(0.0, 1.0 - vectorWeight),
                config.getFusionMethod() == null
                        ? RetrievalConfig.FusionMethod.RECIPROCAL_RANK : config.getFusionMethod(),
                Math.max(1, config.getRrfK()),
                Math.max(0, config.getMaxChunksPerDocument()));
    }

    boolean usesVectors(boolean vectorIndexAvailable) {
        return method != RetrievalMethod.FULL_TEXT && vectorIndexAvailable;
    }

    boolean usesKeywords() {
        return method != RetrievalMethod.VECTOR;
    }

    double fusedScore(double vectorScore, double keywordScore) {
        return switch (method) {
            case VECTOR -> vectorScore;
            case FULL_TEXT -> keywordScore;
            case HYBRID -> vectorWeight * vectorScore + keywordWeight * keywordScore;
        };
    }

    double fusedScore(double vectorScore, double keywordScore, int vectorRank, int keywordRank) {
        if (method != RetrievalMethod.HYBRID
                || fusionMethod == RetrievalConfig.FusionMethod.WEIGHTED_SCORE) {
            return fusedScore(vectorScore, keywordScore);
        }
        double vectorRrf = vectorRank <= 0 ? 0.0 : (double) (rrfK + 1) / (rrfK + vectorRank);
        double keywordRrf = keywordRank <= 0 ? 0.0 : (double) (rrfK + 1) / (rrfK + keywordRank);
        return vectorWeight * vectorRrf + keywordWeight * keywordRrf;
    }
}
