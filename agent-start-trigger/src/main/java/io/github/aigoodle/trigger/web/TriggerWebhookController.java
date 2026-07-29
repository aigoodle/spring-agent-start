package io.github.aigoodle.trigger.web;

import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound webhook endpoint: {@code POST /triggers/webhook/{path}} resolves the matching
 * enabled webhook trigger, verifies an optional token and fires it asynchronously,
 * returning the invocation id.
 */
@RestController
public class TriggerWebhookController {

    private final TriggerService triggerService;

    public TriggerWebhookController(TriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping("/triggers/webhook/{path}")
    public ResponseEntity<?> hook(@PathVariable String path,
                                  @RequestBody(required = false) Map<String, Object> payload,
                                  @RequestParam(required = false) String token,
                                  @RequestHeader(value = "X-Trigger-Token", required = false) String headerToken) {
        Optional<TriggerEntity> found = triggerService.findWebhook(path);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "no webhook trigger for path '" + path + "'"));
        }
        TriggerEntity trigger = found.get();
        Object expected = triggerService.config(trigger).get("token");
        if (expected != null && !String.valueOf(expected).isBlank()) {
            String provided = token != null ? token : headerToken;
            if (!String.valueOf(expected).equals(provided)) {
                return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
            }
        }
        String invocationId = triggerService.fire(trigger.getId(),
                payload == null ? new HashMap<>() : payload, "webhook");
        return ResponseEntity.accepted().body(Map.of("invocationId", invocationId, "triggerId", trigger.getId()));
    }
}
