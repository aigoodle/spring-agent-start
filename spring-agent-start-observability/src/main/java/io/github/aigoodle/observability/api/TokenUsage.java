package io.github.aigoodle.observability.api;

/**
 * Token counts for one LLM call.
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public static TokenUsage of(Integer prompt, Integer completion, Integer total) {
        int p = prompt == null ? 0 : prompt;
        int c = completion == null ? 0 : completion;
        int t = total == null ? p + c : total;
        return new TokenUsage(p, c, t);
    }
}
