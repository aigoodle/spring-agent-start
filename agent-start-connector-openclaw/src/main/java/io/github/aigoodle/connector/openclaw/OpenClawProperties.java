package io.github.aigoodle.connector.openclaw;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "spring-agent.connector.openclaw")
public class OpenClawProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:18789";
    private String serviceToken;
    private String agentId = "main";
    private Duration timeout = Duration.ofSeconds(60);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
