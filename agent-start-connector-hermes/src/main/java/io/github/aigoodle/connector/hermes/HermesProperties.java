package io.github.aigoodle.connector.hermes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "spring-agent.connector.hermes")
public class HermesProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:9119";
    private String apiToken;
    private String bridgeBaseUrl = "http://127.0.0.1:9121";
    private String bridgeToken;
    private Duration timeout = Duration.ofSeconds(15);
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiToken() { return apiToken; } public void setApiToken(String value) { apiToken = value; }
    public String getBridgeBaseUrl() { return bridgeBaseUrl; } public void setBridgeBaseUrl(String value) { bridgeBaseUrl = value; }
    public String getBridgeToken() { return bridgeToken; } public void setBridgeToken(String value) { bridgeToken = value; }
    public Duration getTimeout() { return timeout; } public void setTimeout(Duration value) { timeout = value; }
}
