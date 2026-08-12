package io.github.aigoodle.observability.eval;

import io.github.aigoodle.agent.api.AgentResponse;

import java.time.Duration;
import java.util.List;

public record AgentEvaluationResult(String name, boolean passed, double aggregateScore,
                                    Duration duration, AgentResponse response,
                                    String error, List<AgentEvaluationScore> scores) {
    public AgentEvaluationResult {
        scores = scores == null ? List.of() : List.copyOf(scores);
    }
}
