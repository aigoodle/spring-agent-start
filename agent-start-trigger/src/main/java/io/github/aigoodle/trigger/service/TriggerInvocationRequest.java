package io.github.aigoodle.trigger.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes one request to invoke a trigger.
 *
 * <p>The named factories keep transport-specific source names out of callers and make
 * the invocation API harder to misuse than three adjacent parameters.</p>
 */
public record TriggerInvocationRequest(
        String triggerId,
        Map<String, Object> payload,
        String source) {

    public TriggerInvocationRequest {
        if (triggerId == null || triggerId.isBlank()) {
            throw new IllegalArgumentException("triggerId must not be blank");
        }
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        source = source == null || source.isBlank() ? "manual" : source;
    }

    public static TriggerInvocationRequest manual(String triggerId, Map<String, Object> payload) {
        return new TriggerInvocationRequest(triggerId, payload, "manual");
    }

    public static TriggerInvocationRequest webhook(String triggerId, Map<String, Object> payload) {
        return new TriggerInvocationRequest(triggerId, payload, "webhook");
    }

    public static TriggerInvocationRequest event(String triggerId, Map<String, Object> payload) {
        return new TriggerInvocationRequest(triggerId, payload, "event");
    }

    public static TriggerInvocationRequest cron(String triggerId) {
        return new TriggerInvocationRequest(triggerId, Map.of(), "cron");
    }
}
