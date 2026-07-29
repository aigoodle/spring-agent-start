package io.github.aigoodle.agent.service;

import lombok.Builder;
import lombok.Data;

/** Compact per-app metrics view returned by {@link AppMetricsService#compute}. */
@Data
@Builder
public class AppMetricsView {
    private String appId;
    /** Distinct conversation ids seen for this app. */
    private int totalConversations;
    /** Total messages (both roles) recorded for this app. */
    private int totalMessages;
    private int userMessages;
    private int assistantMessages;
    /** userMessages / totalConversations, rounded to 2 decimals. */
    private double avgInteractionsPerConversation;
    /** ISO-8601 of the most recent message; {@code null} when the app has none. */
    private String lastActivityAt;
}
