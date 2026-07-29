package io.github.aigoodle.trigger.api;

/**
 * How a trigger fires.
 */
public enum TriggerType {

    /** Fired by an inbound HTTP call to a webhook path. */
    WEBHOOK,

    /** Fired on a cron schedule. */
    CRON,

    /** Fired when a named internal event is published. */
    EVENT,

    /** Fired only by an explicit API call. */
    MANUAL
}
