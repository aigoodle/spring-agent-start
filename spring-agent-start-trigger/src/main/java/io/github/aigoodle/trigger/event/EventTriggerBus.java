package io.github.aigoodle.trigger.event;

import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small in-process event bus: publishing a named event fires every enabled EVENT
 * trigger whose configured {@code eventName} matches.
 */
public class EventTriggerBus {

    private final ObjectProvider<TriggerService> triggerService;

    public EventTriggerBus(ObjectProvider<TriggerService> triggerService) {
        this.triggerService = triggerService;
    }

    /** Fire matching triggers asynchronously; returns the started invocation ids. */
    public List<String> publish(String eventName, Map<String, Object> payload) {
        TriggerService service = triggerService.getObject();
        List<String> invocationIds = new ArrayList<>();
        for (TriggerEntity t : matching(service, eventName)) {
            invocationIds.add(service.fire(t.getId(), payload, "event"));
        }
        return invocationIds;
    }

    /** Fire matching triggers synchronously (handy for tests / request-scoped flows). */
    public int publishSync(String eventName, Map<String, Object> payload) {
        TriggerService service = triggerService.getObject();
        int fired = 0;
        for (TriggerEntity t : matching(service, eventName)) {
            service.fireSync(t.getId(), payload, "event");
            fired++;
        }
        return fired;
    }

    private List<TriggerEntity> matching(TriggerService service, String eventName) {
        List<TriggerEntity> matched = new ArrayList<>();
        for (TriggerEntity t : service.listEnabledByType(TriggerType.EVENT)) {
            if (eventName != null && eventName.equals(String.valueOf(service.config(t).get("eventName")))) {
                matched.add(t);
            }
        }
        return matched;
    }
}
