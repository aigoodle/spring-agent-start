package io.github.aigoodle.connector.hermes;

import io.github.aigoodle.connector.channel.ChannelAccount;
import io.github.aigoodle.connector.channel.ChannelDefinition;
import io.github.aigoodle.connector.channel.SaveChannelAccountRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HermesChannelRuntimeProviderTest {
    @Test void exposesOfficialPlatformsAndRuntimeState() {
        HermesDashboardClient client = mock(HermesDashboardClient.class);
        HermesBridgeClient bridge = mock(HermesBridgeClient.class);
        when(client.platforms(null)).thenReturn(Map.of("platforms", Map.of("qqbot", Map.of("enabled", true, "running", true))));

        List<ChannelDefinition> channels = new HermesChannelRuntimeProvider(client, bridge).discoverChannels();

        assertThat(channels).hasSize(20);
        ChannelDefinition qq = channels.stream().filter(it -> it.channelId().equals("qqbot")).findFirst().orElseThrow();
        assertThat(qq.runtimeStatus()).isEqualTo("ONLINE");
        assertThat(qq.metadata()).containsEntry("platformId", "qq").containsEntry("routingMode", "HERMES_NATIVE");
        assertThat(qq.capabilities()).containsEntry("agentStartInbound", false)
                .containsEntry("deliveryReceipts", false)
                .containsEntry("readReceipts", false);
    }

    @Test void mapsOneConnectionToOneHermesProfile() {
        HermesDashboardClient client = mock(HermesDashboardClient.class);
        HermesBridgeClient bridge = mock(HermesBridgeClient.class);
        when(client.savePlatform(eq("qqbot"), eq("connection-id"), anyMap())).thenReturn(Map.of("running", true));
        HermesChannelRuntimeProvider provider = new HermesChannelRuntimeProvider(client, bridge);

        ChannelAccount account = provider.saveAccount(new SaveChannelAccountRequest(
                "qqbot", "connection-id", "QQ", true, Map.of("profile", "attempted-cross-tenant-profile",
                "appId", "app-7", "clientSecret", "secret")));

        assertThat(account.accountId()).isEqualTo("connection-id");
        verify(client).savePlatform("qqbot", "connection-id", Map.of("enabled", true, "profile", "connection-id",
                "env", Map.of("QQ_APP_ID", "app-7", "QQ_CLIENT_SECRET", "secret"), "clear_env", List.of()));
    }

    @Test void discoversRuntimeProfilesWithReachabilityAndConnectionState() {
        HermesDashboardClient client = mock(HermesDashboardClient.class);
        HermesBridgeClient bridge = mock(HermesBridgeClient.class);
        when(bridge.status()).thenReturn(Map.of("ok", true, "profileStates", List.of(
                Map.of("profile", "employee-7", "platform", "qqbot", "reachable", true,
                        "connected", true, "pendingInboundCallbacks", 0,
                        "callbackWorkerRunning", true, "callbackWorkerFailures", 0),
                Map.of("profile", "employee-8", "platform", "qqbot", "reachable", true,
                        "connected", false, "pendingInboundCallbacks", 2,
                        "callbackWorkerRunning", false, "callbackWorkerFailures", 3,
                        "callbackWorkerError", "database is locked"),
                Map.of("profile", "employee-9", "platform", "telegram", "reachable", true,
                        "connected", true))));

        List<ChannelAccount> accounts = new HermesChannelRuntimeProvider(client, bridge).accounts("qqbot");

        assertThat(accounts).extracting(ChannelAccount::accountId)
                .containsExactly("employee-7", "employee-8");
        assertThat(accounts.getFirst().running()).isTrue();
        assertThat(accounts.getFirst().connected()).isTrue();
        assertThat(accounts.get(1).running()).isTrue();
        assertThat(accounts.get(1).connected()).isFalse();
        assertThat(accounts.get(1).metadata()).containsEntry("pendingInboundCallbacks", 2)
                .containsEntry("callbackWorkerRunning", false)
                .containsEntry("callbackWorkerFailures", 3)
                .containsEntry("callbackWorkerError", "database is locked");
    }

    @Test void advertisesAndUsesAgentStartBridgeOnlyWhenHealthy() {
        HermesDashboardClient client = mock(HermesDashboardClient.class);
        HermesBridgeClient bridge = mock(HermesBridgeClient.class);
        when(client.platforms(null)).thenReturn(Map.of()); when(bridge.healthy()).thenReturn(true);
        HermesChannelRuntimeProvider provider = new HermesChannelRuntimeProvider(client, bridge);

        ChannelDefinition qq = provider.discoverChannels().stream()
                .filter(it -> it.channelId().equals("qqbot")).findFirst().orElseThrow();

        assertThat(qq.metadata()).containsEntry("routingMode", "AGENT_START");
        assertThat(qq.capabilities()).containsEntry("agentStartInbound", true)
                .containsEntry("agentStartOutbound", true);
        ChannelDefinition telegram = provider.discoverChannels().stream()
                .filter(it -> it.channelId().equals("telegram")).findFirst().orElseThrow();
        assertThat(telegram.metadata()).containsEntry("routingMode", "HERMES_NATIVE");
        assertThat(telegram.capabilities()).containsEntry("agentStartInbound", false);
    }
}
