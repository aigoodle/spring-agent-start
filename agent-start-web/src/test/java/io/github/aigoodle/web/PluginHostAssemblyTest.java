package io.github.aigoodle.web;

import io.github.aigoodle.plugin.host.PluginHostApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

/** Uses real auto-configuration and the host's prefixed HTTP route. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WorkflowSaveHttpTest.TestApp.class, properties = {
        "spring-agent.plugin.host-signing-secret=assembly-test-secret-at-least-32-bytes",
        "spring-agent.plugin.host-base-url=http://127.0.0.1/agent-start/plugin-host/v1"})
@AutoConfigureWebTestClient
class PluginHostAssemblyTest {
    @Autowired WebTestClient http;
    @Autowired PluginHostApi host;
    @Test void enabledAutoConfigurationRegistersAuthenticatedModelRoute() {
        assertThat(host).isNotNull();
        http.post().uri("/agent-start/plugin-host/v1/models/chat")
                .bodyValue(java.util.Map.of()).exchange().expectStatus().isUnauthorized();
        http.post().uri("/agent-start/plugin-host/v1/models/chat")
                .header("Authorization", "Bearer invalid")
                .bodyValue(java.util.Map.of()).exchange().expectStatus().isUnauthorized();
    }
}
