package io.github.aigoodle.web.dto;

import io.github.aigoodle.rag.api.RagPolicy;
import java.util.Map;

public record RagRetrieveRequestDto(String query, String conversationId, RagPolicy policy,
                                    Map<String, Object> metadataFilter) {}
