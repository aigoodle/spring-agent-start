package io.github.aigoodle.web.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/** Body for both {@code /agents/{id}/chat} and its SSE variant. */
@Data
public class ChatRequest {

    private String query;
    private String conversationId;
    private Map<String, Object> variables = new HashMap<>();
}
