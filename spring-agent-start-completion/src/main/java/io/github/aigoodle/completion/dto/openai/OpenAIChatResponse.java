package io.github.aigoodle.completion.dto.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI-compatible chat completion response — both the blocking response
 * ({@code object = chat.completion}) and each streaming chunk
 * ({@code object = chat.completion.chunk}) share this envelope.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIChatResponse {

    public static final String OBJECT_COMPLETION = "chat.completion";
    public static final String OBJECT_CHUNK = "chat.completion.chunk";

    private String id;
    private String object;
    private Long created;
    private String model;
    private List<OpenAIChoice> choices = new ArrayList<>();
    private OpenAIUsage usage;

    public static OpenAIChatResponse completion(String model, String answer) {
        OpenAIChatResponse resp = new OpenAIChatResponse();
        resp.setId("chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
        resp.setObject(OBJECT_COMPLETION);
        resp.setCreated(Instant.now().getEpochSecond());
        resp.setModel(model == null ? "spring-agent" : model);
        resp.getChoices().add(OpenAIChoice.message(0, OpenAIMessage.assistant(answer), "stop"));
        resp.setUsage(new OpenAIUsage());
        return resp;
    }

    public static OpenAIChatResponse chunk(String id, String model, String role,
                                           String contentDelta, String finishReason) {
        OpenAIChatResponse resp = new OpenAIChatResponse();
        resp.setId(id);
        resp.setObject(OBJECT_CHUNK);
        resp.setCreated(Instant.now().getEpochSecond());
        resp.setModel(model == null ? "spring-agent" : model);
        OpenAIDelta delta = new OpenAIDelta();
        if (role != null) delta.setRole(role);
        if (contentDelta != null) delta.setContent(contentDelta);
        resp.getChoices().add(OpenAIChoice.delta(0, delta, finishReason));
        return resp;
    }
}
