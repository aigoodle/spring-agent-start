package io.github.aigoodle.common.exception;

/**
 * Base unchecked exception for all spring-agent-start modules.
 */
public class AgentException extends RuntimeException {

    private final String code;

    public AgentException(String message) {
        this("agent_error", message, null);
    }

    public AgentException(String message, Throwable cause) {
        this("agent_error", message, cause);
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static AgentException of(String message) {
        return new AgentException(message);
    }
}
