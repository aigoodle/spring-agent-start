package io.github.aigoodle.connector.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ChannelConnectionEntity;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ChannelConnectionServiceTest {
  @Test
  void nativeEmployeeAccountNeverPersistsApplicationBinding() {
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ConnectorSecretCodec codec = mock(ConnectorSecretCodec.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("native");
    when(mapper.insert(any(ChannelConnectionEntity.class))).thenReturn(1);
    when(runtime.saveAccount(any()))
        .thenAnswer(
            invocation -> {
              SaveChannelAccountRequest request = invocation.getArgument(0);
              return new ChannelAccount(
                  "native",
                  "qqbot",
                  request.accountId(),
                  request.name(),
                  true,
                  true,
                  true,
                  true,
                  null,
                  null,
                  Map.of());
            });
    ChannelConnectionService service =
        new ChannelConnectionService(mapper, codec, new ChannelRuntimeRegistry(List.of(runtime)));

    ChannelConnectionService.View saved =
        service.save(
            new ChannelConnectionService.SaveRequest(
                null,
                "tenant-a",
                "USER",
                "employee-1",
                "native",
                "qqbot",
                "员工 QQ",
                null,
                null,
                true,
                "application-that-must-not-be-bound",
                "version-1",
                null));

    assertThat(saved.ownerId()).isEqualTo("employee-1");
    assertThat(saved.agentId()).isNull();
    assertThat(saved.agentVersionId()).isNull();
    ArgumentCaptor<ChannelConnectionEntity> inserted =
        ArgumentCaptor.forClass(ChannelConnectionEntity.class);
    verify(mapper).insert(inserted.capture());
    assertThat(inserted.getValue().getAgentId()).isNull();
    assertThat(inserted.getValue().getAgentVersionId()).isNull();
  }

  @Test
  void runtimeNodeDisambiguatesIdenticalProviderAccountIdsAndLegacyLookupFailsClosed() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-node-scope"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ChannelConnectionEntity nodeA = runtimeConnection("connection-a", "tenant-a", "node-a");
    ChannelConnectionEntity nodeB = runtimeConnection("connection-b", "tenant-b", "node-b");
    when(mapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              var query =
                  (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                          ChannelConnectionEntity>)
                      invocation.getArgument(0);
              String sql = query.getSqlSegment();
              if (sql.contains("runtime_node_id")) {
                return query.getParamNameValuePairs().containsValue("node-b")
                    ? List.of(nodeB)
                    : List.of(nodeA);
              }
              return List.of(nodeA, nodeB);
            });
    ChannelConnectionService service =
        new ChannelConnectionService(
            mapper, mock(ConnectorSecretCodec.class), new ChannelRuntimeRegistry(List.of()));

    assertThat(service.ownership("openclaw", "node-b", "qqbot", "shared-account"))
        .extracting(
            ChannelConnectionService.Ownership::tenantId,
            ChannelConnectionService.Ownership::runtimeNodeId)
        .containsExactly("tenant-b", "node-b");
    assertThat(service.ownership("openclaw", "qqbot", "shared-account")).isNull();
  }

  private static ChannelConnectionEntity runtimeConnection(
      String id, String tenantId, String nodeId) {
    ChannelConnectionEntity entity = new ChannelConnectionEntity();
    entity.setId(id);
    entity.setTenantId(tenantId);
    entity.setOwnerId("employee");
    entity.setProvider("openclaw");
    entity.setChannelId("qqbot");
    entity.setRuntimeAccountId("shared-account");
    entity.setRuntimeNodeId(nodeId);
    return entity;
  }

  @Test
  void createsOwnedConnectionAndInjectsCredentialsIntoRuntimeWithoutReturningThem() {
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ConnectorSecretCodec codec = mock(ConnectorSecretCodec.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("openclaw");
    when(codec.encode("tenant-a", Map.of("appId", "app", "clientSecret", "secret")))
        .thenReturn("encrypted-creds");
    when(codec.encode("tenant-a", Map.of("markdownSupport", true))).thenReturn("encrypted-config");
    when(codec.decode("tenant-a", "encrypted-creds"))
        .thenReturn(Map.of("appId", "app", "clientSecret", "secret"));
    when(codec.decode("tenant-a", "encrypted-config")).thenReturn(Map.of("markdownSupport", true));
    when(mapper.insert(any(ChannelConnectionEntity.class))).thenReturn(1);
    when(runtime.saveAccount(any()))
        .thenAnswer(
            invocation -> {
              SaveChannelAccountRequest saved = invocation.getArgument(0);
              return new ChannelAccount(
                  "openclaw",
                  "qqbot",
                  saved.accountId(),
                  "客服机器人",
                  true,
                  true,
                  false,
                  false,
                  null,
                  null,
                  Map.of());
            });

    ChannelConnectionService service =
        new ChannelConnectionService(mapper, codec, new ChannelRuntimeRegistry(List.of(runtime)));
    ChannelConnectionService.View view =
        service.save(
            new ChannelConnectionService.SaveRequest(
                null,
                "tenant-a",
                "USER",
                "employee-1",
                "openclaw",
                "qqbot",
                "客服机器人",
                Map.of("appId", "app", "clientSecret", "secret"),
                Map.of("markdownSupport", true),
                true,
                "agent-1",
                null));

    ArgumentCaptor<SaveChannelAccountRequest> request =
        ArgumentCaptor.forClass(SaveChannelAccountRequest.class);
    verify(runtime).saveAccount(request.capture());
    assertThat(request.getValue().accountId()).isNotBlank();
    assertThat(request.getValue().configuration())
        .containsEntry("clientSecret", "secret")
        .containsEntry("markdownSupport", true);
    assertThat(view.credentialsConfigured()).isTrue();
    assertThat(view.runtimeAccountId()).isEqualTo(view.id());
    assertThat(view.agentId()).isEqualTo("agent-1");
    assertThat(view.runtimeNodeId()).isEqualTo("openclaw-default");
    assertThat(view.toString()).doesNotContain("secret");

    ArgumentCaptor<ChannelConnectionEntity> inserted =
        ArgumentCaptor.forClass(ChannelConnectionEntity.class);
    verify(mapper).insert(inserted.capture());
    assertThat(inserted.getValue().getRuntimeAccountId()).isEqualTo(inserted.getValue().getId());
  }

  @Test
  void reconcilerRecoversAPendingConnectionUsingItsPersistedRuntimeIdentity() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-reconcile"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ConnectorSecretCodec codec = mock(ConnectorSecretCodec.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("hermes");
    ChannelConnectionEntity pending = new ChannelConnectionEntity();
    pending.setId("connection-7");
    pending.setTenantId("tenant-a");
    pending.setOwnerId("employee-7");
    pending.setProvider("hermes");
    pending.setChannelId("qqbot");
    pending.setName("QQ");
    pending.setRuntimeNodeId("hermes-default");
    pending.setRuntimeAccountId("connection-7");
    pending.setRuntimeStatus("PENDING");
    pending.setDesiredStatus("ACTIVE");
    pending.setReconcileAttempts(0);
    pending.setEncryptedCredentials("encrypted");
    when(mapper.update(isNull(), any())).thenReturn(1);
    when(mapper.selectOne(any())).thenReturn(pending);
    when(codec.decode("tenant-a", "encrypted"))
        .thenReturn(Map.of("appId", "app", "clientSecret", "secret"));
    when(runtime.saveAccount(any()))
        .thenReturn(
            new ChannelAccount(
                "hermes",
                "qqbot",
                "connection-7",
                "QQ",
                true,
                true,
                true,
                true,
                LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toInstant(),
                null,
                Map.of()));
    ChannelConnectionService service =
        new ChannelConnectionService(mapper, codec, new ChannelRuntimeRegistry(List.of(runtime)));

    boolean recovered =
        service.reconcile(
            new ChannelConnectionService.ReconcileCandidate("connection-7", "tenant-a"),
            "worker-1",
            LocalDateTime.now(),
            Duration.ofSeconds(30));

    assertThat(recovered).isTrue();
    ArgumentCaptor<SaveChannelAccountRequest> request =
        ArgumentCaptor.forClass(SaveChannelAccountRequest.class);
    verify(runtime).saveAccount(request.capture());
    assertThat(request.getValue().accountId()).isEqualTo("connection-7");
    assertThat(request.getValue().configuration()).containsEntry("clientSecret", "secret");
    verify(mapper, times(2)).update(isNull(), any());
  }

  @Test
  void concurrentReconcilerThatLosesTheDatabaseLeaseNeverTouchesTheRuntime() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-lease-loser"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("openclaw");
    when(mapper.update(isNull(), any())).thenReturn(0);
    ChannelConnectionService service =
        new ChannelConnectionService(
            mapper, mock(ConnectorSecretCodec.class), new ChannelRuntimeRegistry(List.of(runtime)));

    boolean reconciled =
        service.reconcile(
            new ChannelConnectionService.ReconcileCandidate("connection-7", "tenant-a"),
            "worker-loser",
            LocalDateTime.now(),
            Duration.ofSeconds(30));

    assertThat(reconciled).isFalse();
    verify(mapper, never()).selectOne(any());
    verify(runtime, never()).saveAccount(any());
  }

  @Test
  void persistsUpdatedDesiredStateBeforeCallingTheExternalRuntime() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-update"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ConnectorSecretCodec codec = mock(ConnectorSecretCodec.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("openclaw");
    ChannelConnectionEntity existing = new ChannelConnectionEntity();
    existing.setId("connection-8");
    existing.setTenantId("tenant-a");
    existing.setOwnerId("employee-8");
    existing.setProvider("openclaw");
    existing.setChannelId("qqbot");
    existing.setName("Old QQ");
    existing.setRuntimeNodeId("openclaw-default");
    existing.setRuntimeAccountId("connection-8");
    existing.setConfigVersion(4L);
    existing.setEncryptedCredentials("old-encrypted");
    when(mapper.selectOne(any())).thenReturn(existing);
    when(codec.encode("tenant-a", Map.of("appId", "new-app", "clientSecret", "new-secret")))
        .thenReturn("new-encrypted");
    when(codec.decode("tenant-a", "new-encrypted"))
        .thenReturn(Map.of("appId", "new-app", "clientSecret", "new-secret"));
    when(runtime.saveAccount(any()))
        .thenReturn(
            new ChannelAccount(
                "openclaw",
                "qqbot",
                "connection-8",
                "New QQ",
                true,
                true,
                true,
                true,
                null,
                null,
                Map.of()));
    ChannelConnectionService service =
        new ChannelConnectionService(mapper, codec, new ChannelRuntimeRegistry(List.of(runtime)));

    service.save(
        new ChannelConnectionService.SaveRequest(
            "connection-8",
            "tenant-a",
            "USER",
            "employee-8",
            "openclaw",
            "qqbot",
            "New QQ",
            Map.of("appId", "new-app", "clientSecret", "new-secret"),
            null,
            true,
            "agent-8",
            null));

    InOrder order = inOrder(mapper, runtime);
    order.verify(mapper).update(eq(existing), any());
    order.verify(runtime).saveAccount(any());
    order.verify(mapper).update(eq(existing), any());
    assertThat(existing.getRuntimeStatus()).isEqualTo("ONLINE");
    assertThat(existing.getConfigVersion()).isEqualTo(5L);
  }

  @Test
  void persistsAndReturnsNonSecretRuntimeHealthMetadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-health"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("hermes");
    ChannelConnectionEntity existing = new ChannelConnectionEntity();
    existing.setId("connection-health-1");
    existing.setTenantId("tenant-a");
    existing.setOwnerId("employee-7");
    existing.setOwnerType("USER");
    existing.setProvider("hermes");
    existing.setChannelId("qqbot");
    existing.setName("Hermes QQ");
    existing.setRuntimeNodeId("hermes-default");
    existing.setRuntimeAccountId("connection-health-1");
    existing.setRuntimeStatus("ONLINE");
    existing.setDesiredStatus("ACTIVE");
    existing.setConfigVersion(1L);
    when(mapper.selectOne(any())).thenReturn(existing);
    when(runtime.testAccount("qqbot", "connection-health-1"))
        .thenReturn(
            new ChannelAccount(
                "hermes",
                "qqbot",
                "connection-health-1",
                "Hermes QQ",
                true,
                true,
                true,
                true,
                null,
                null,
                Map.of(
                    "callbackWorkerRunning",
                    false,
                    "callbackWorkerFailures",
                    2,
                    "callbackWorkerError",
                    "database is locked",
                    "pendingInboundCallbacks",
                    3)));
    ChannelConnectionService service =
        new ChannelConnectionService(
            mapper, mock(ConnectorSecretCodec.class), new ChannelRuntimeRegistry(List.of(runtime)));

    ChannelConnectionService.View tested = service.test("connection-health-1", "tenant-a");
    ChannelConnectionService.View reloaded = service.get("connection-health-1", "tenant-a");

    assertThat(existing.getRuntimeMetadataJson()).contains("callbackWorkerRunning");
    assertThat(tested.runtimeMetadata())
        .containsEntry("callbackWorkerRunning", false)
        .containsEntry("pendingInboundCallbacks", 3);
    assertThat(reloaded.runtimeMetadata()).isEqualTo(tested.runtimeMetadata());
    assertThat(reloaded.toString()).doesNotContain("clientSecret");
    verify(mapper).update(eq(existing), any());
  }

  @Test
  void backgroundHealthRefreshUsesLeaseAndProbesTheOwnedRuntimeAccount() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-health-worker"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("hermes");
    ChannelConnectionEntity existing = new ChannelConnectionEntity();
    existing.setId("connection-7");
    existing.setTenantId("tenant-a");
    existing.setOwnerId("employee-7");
    existing.setProvider("hermes");
    existing.setChannelId("qqbot");
    existing.setRuntimeNodeId("hermes-default");
    existing.setRuntimeAccountId("connection-7");
    existing.setDesiredStatus("ACTIVE");
    existing.setRuntimeStatus("ONLINE");
    when(mapper.update(isNull(), any())).thenReturn(1);
    when(mapper.selectOne(any())).thenReturn(existing);
    when(runtime.testAccount("qqbot", "connection-7"))
        .thenReturn(
            new ChannelAccount(
                "hermes",
                "qqbot",
                "connection-7",
                "QQ",
                true,
                true,
                true,
                true,
                null,
                null,
                Map.of("callbackWorkerRunning", true, "pendingInboundCallbacks", 0)));
    ChannelConnectionService service =
        new ChannelConnectionService(
            mapper, mock(ConnectorSecretCodec.class), new ChannelRuntimeRegistry(List.of(runtime)));

    boolean healthy =
        service.refreshHealth(
            new ChannelConnectionService.HealthCandidate("connection-7", "tenant-a"),
            "health-worker-1",
            LocalDateTime.now(),
            Duration.ofSeconds(30));

    assertThat(healthy).isTrue();
    verify(runtime).testAccount("qqbot", "connection-7");
    verify(mapper, times(2)).update(isNull(), any());
  }

  @Test
  void healthWorkerThatLosesItsLeaseDoesNotProbeTheRuntime() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "connection-health-lease"),
        ChannelConnectionEntity.class);
    ChannelConnectionMapper mapper = mock(ChannelConnectionMapper.class);
    ChannelRuntimeProvider runtime = mock(ChannelRuntimeProvider.class);
    when(runtime.type()).thenReturn("hermes");
    when(mapper.update(isNull(), any())).thenReturn(0);
    ChannelConnectionService service =
        new ChannelConnectionService(
            mapper, mock(ConnectorSecretCodec.class), new ChannelRuntimeRegistry(List.of(runtime)));

    boolean refreshed =
        service.refreshHealth(
            new ChannelConnectionService.HealthCandidate("connection-7", "tenant-a"),
            "health-worker-loser",
            LocalDateTime.now(),
            Duration.ofSeconds(30));

    assertThat(refreshed).isFalse();
    verify(runtime, never()).testAccount(any(), any());
    verify(mapper, never()).selectOne(any());
  }
}
