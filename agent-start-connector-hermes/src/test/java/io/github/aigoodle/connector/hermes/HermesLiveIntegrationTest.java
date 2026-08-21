package io.github.aigoodle.connector.hermes;

import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in smoke and outbound test against the real Dashboard and authenticated bridge. */
class HermesLiveIntegrationTest {
    @Test
    void dashboardAndBridgeAreHealthy() {
        Assumptions.assumeTrue(enabled());
        HermesProperties properties = properties();

        assertThat(new RestHermesDashboardClient(properties).health()).isNotEmpty();
        assertThat(new RestHermesBridgeClient(properties).healthy()).isTrue();
    }

    @Test
    void sendsARealQqMessageWhenExplicitProfileAndTargetAreConfigured() {
        Assumptions.assumeTrue(enabled());
        String profile = required("HERMES_LIVE_PROFILE");
        String target = required("HERMES_LIVE_TARGET");
        var result = new RestHermesBridgeClient(properties()).send(new ChannelOutboundMessage(
                "qqbot", profile, target, "live-" + Instant.now().toEpochMilli(),
                "Agent Start Hermes E2E " + Instant.now(), Map.of("test", true)));

        assertThat(result.metadata()).containsEntry("success", true);
    }

    private static HermesProperties properties() {
        HermesProperties properties = new HermesProperties();
        properties.setBaseUrl(value("HERMES_BASE_URL", "http://127.0.0.1:9119"));
        properties.setBridgeBaseUrl(value("HERMES_BRIDGE_BASE_URL", "http://127.0.0.1:9121"));
        properties.setBridgeToken(required("HERMES_BRIDGE_TOKEN"));
        String apiToken = System.getenv("HERMES_API_TOKEN");
        if (apiToken != null && !apiToken.isBlank()) properties.setApiToken(apiToken.trim());
        return properties;
    }

    private static boolean enabled() {
        return Boolean.parseBoolean(System.getenv("HERMES_LIVE_TEST"));
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value.trim();
    }
}
