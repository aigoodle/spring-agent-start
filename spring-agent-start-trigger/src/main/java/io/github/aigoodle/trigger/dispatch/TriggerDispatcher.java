package io.github.aigoodle.trigger.dispatch;

import java.util.Map;

/**
 * Runs a trigger's target. The built-in dispatcher runs workflows; publish another
 * bean (e.g. one that runs an agent) to add a new {@code targetType} — the trigger
 * module stays decoupled from the agent module this way.
 */
public interface TriggerDispatcher {

    /** The {@code targetType} this dispatcher handles, e.g. {@code "workflow"}. */
    String targetType();

    DispatchResult dispatch(String targetId, Map<String, Object> inputs, String conversationId);
}
