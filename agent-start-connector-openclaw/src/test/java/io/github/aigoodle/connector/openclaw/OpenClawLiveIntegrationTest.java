package io.github.aigoodle.connector.openclaw;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in contract test against a real OpenClaw Gateway with agent-start-bridge installed. */
class OpenClawLiveIntegrationTest {
    @Test
    void discoversRealGatewayPluginsAndTools() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("OPENCLAW_LIVE_TEST")));
        OpenClawProperties properties = new OpenClawProperties();
        properties.setBaseUrl(value("OPENCLAW_BASE_URL", "http://127.0.0.1:19091"));
        properties.setServiceToken(value("OPENCLAW_SERVICE_TOKEN", "e2e-bridge-token"));
        RestOpenClawGatewayClient client = new RestOpenClawGatewayClient(properties);

        assertThat(client.runtime().status()).isEqualTo("UP");
        assertThat(client.plugins()).isNotEmpty();
        assertThat(client.tools()).isNotEmpty();
        assertThat(new OpenClawConnectorProvider(client).discover()).isNotEmpty();
    }

    @Test
    void sendsARealChannelMessageWhenExplicitTargetIsConfigured() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("OPENCLAW_LIVE_TEST")));
        String channel = required("OPENCLAW_LIVE_CHANNEL");
        String account = required("OPENCLAW_LIVE_ACCOUNT");
        String target = required("OPENCLAW_LIVE_TARGET");
        OpenClawProperties properties = new OpenClawProperties();
        properties.setBaseUrl(value("OPENCLAW_BASE_URL", "http://127.0.0.1:18789"));
        properties.setServiceToken(required("OPENCLAW_SERVICE_TOKEN"));
        RestOpenClawGatewayClient client = new RestOpenClawGatewayClient(properties);

        assertThat(client.testChannelAccount(channel, account).configured()).isTrue();
        assertThat(client.sendChannelMessageWithResult(channel, account, target,
                "Agent Start E2E " + java.time.Instant.now())).isNotNull();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value.trim();
    }
}
