package io.github.aigoodle.connector.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.connector.ConnectorException;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ChannelConnectionEntity;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import io.github.aigoodle.connector.persistence.ConnectorTenantScope;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores user/tenant channel connections and reconciles their desired state to a runtime. */
public class ChannelConnectionService {
  public record ReconcileCandidate(String id, String tenantId) {}

  public record HealthCandidate(String id, String tenantId) {}

  public record SaveRequest(
      String id,
      String tenantId,
      String ownerType,
      String ownerId,
      String provider,
      String channelId,
      String name,
      Map<String, Object> credentials,
      Map<String, Object> config,
      Boolean enabled,
      String agentId,
      String agentVersionId,
      String runtimeNodeId) {
    public SaveRequest(
        String id,
        String tenantId,
        String ownerType,
        String ownerId,
        String provider,
        String channelId,
        String name,
        Map<String, Object> credentials,
        Map<String, Object> config,
        Boolean enabled,
        String agentId,
        String runtimeNodeId) {
      this(
          id,
          tenantId,
          ownerType,
          ownerId,
          provider,
          channelId,
          name,
          credentials,
          config,
          enabled,
          agentId,
          null,
          runtimeNodeId);
    }
  }

  public record View(
      String id,
      String tenantId,
      String ownerType,
      String ownerId,
      String provider,
      String channelId,
      String name,
      String desiredStatus,
      String runtimeStatus,
      String runtimeAccountId,
      String agentId,
      String agentVersionId,
      String runtimeNodeId,
      Map<String, Object> runtimeMetadata,
      boolean credentialsConfigured,
      String lastError,
      LocalDateTime lastTestedAt,
      long configVersion) {}

  public record Route(String connectionId, String tenantId, String ownerId, String agentId) {}

  public record Ownership(
      String connectionId, String tenantId, String ownerId, String runtimeNodeId) {}

  public record RoutingConnection(
      String connectionId,
      String tenantId,
      String ownerId,
      String explicitAgentId,
      String explicitAgentVersionId,
      boolean active) {
    public RoutingConnection(
        String connectionId,
        String tenantId,
        String ownerId,
        String explicitAgentId,
        boolean active) {
      this(connectionId, tenantId, ownerId, explicitAgentId, null, active);
    }
  }

  private final ChannelConnectionMapper mapper;
  private final ConnectorSecretCodec codec;
  private final ChannelRuntimeRegistry runtimes;

  public ChannelConnectionService(
      ChannelConnectionMapper mapper, ConnectorSecretCodec codec, ChannelRuntimeRegistry runtimes) {
    this.mapper = mapper;
    this.codec = codec;
    this.runtimes = runtimes;
  }

  public View save(SaveRequest request) {
    String tenantId = tenant(request.tenantId());
    ChannelConnectionEntity entity =
        request.id() == null ? new ChannelConnectionEntity() : requireOwned(request.id(), tenantId);
    boolean creating = entity.getId() == null;
    entity.setTenantId(tenantId);
    entity.setOwnerType(defaulted(request.ownerType(), "USER"));
    entity.setOwnerId(required(request.ownerId(), "ownerId"));
    entity.setProvider(required(request.provider(), "provider"));
    entity.setChannelId(required(request.channelId(), "channelId"));
    entity.setName(required(request.name(), "name"));
    // Native bot credentials belong to an employee. Applications/workflows subscribe to the
    // channel separately and must never be embedded into an employee account record.
    boolean nativeChannel = "native".equalsIgnoreCase(entity.getProvider());
    entity.setAgentId(nativeChannel ? null : trimToNull(request.agentId()));
    entity.setAgentVersionId(nativeChannel ? null : trimToNull(request.agentVersionId()));
    if (creating)
      entity.setRuntimeNodeId(selectNode(entity.getProvider(), request.runtimeNodeId()));
    if (request.credentials() != null)
      entity.setEncryptedCredentials(codec.encode(tenantId, request.credentials()));
    if (request.config() != null)
      entity.setEncryptedConfig(codec.encode(tenantId, request.config()));
    entity.setDesiredStatus(Boolean.FALSE.equals(request.enabled()) ? "DISABLED" : "ACTIVE");
    entity.setRuntimeStatus("PENDING");
    entity.setReconcileAttempts(0);
    entity.setNextReconcileAt(LocalDateTime.now());
    entity.setReconcileLeaseUntil(null);
    entity.setReconcileLeaseOwner(null);
    entity.setConfigVersion(
        entity.getConfigVersion() == null ? 1L : entity.getConfigVersion() + 1L);
    if (creating) {
      // Reserve the globally unique runtime identity in the ownership database before invoking
      // an external runtime. A crash can leave a reconcilable PENDING row, never an unowned
      // account.
      entity.setId(UUID.randomUUID().toString().replace("-", ""));
      entity.setRuntimeAccountId(entity.getId());
      mapper.insert(entity);
    } else {
      // Persist the desired state before touching the external runtime. If this process exits
      // during saveAccount, the reconciler can replay the exact encrypted configuration.
      updateOwned(entity);
    }
    try {
      ChannelAccount account =
          runtimes
              .require(entity.getProvider(), entity.getRuntimeNodeId())
              .saveAccount(
                  new SaveChannelAccountRequest(
                      entity.getChannelId(),
                      entity.getRuntimeAccountId(),
                      entity.getName(),
                      "ACTIVE".equals(entity.getDesiredStatus()),
                      runtimeConfig(entity)));
      requireStableRuntimeAccount(entity, account);
      entity.setRuntimeStatus(
          account.connected() ? "ONLINE" : account.running() ? "RUNNING" : "STARTING");
      entity.setRuntimeMetadataJson(metadataJson(account));
      entity.setLastError(account.lastError());
      entity.setNextReconcileAt(null);
    } catch (RuntimeException ex) {
      entity.setRuntimeStatus("ERROR");
      entity.setLastError(safeMessage(ex));
      entity.setReconcileAttempts(1);
      entity.setNextReconcileAt(LocalDateTime.now().plusSeconds(2));
    }
    updateOwned(entity);
    return view(entity);
  }

  /** Cross-tenant scan is restricted to the trusted embedded reconciler. */
  public List<ReconcileCandidate> reconciliationCandidates(LocalDateTime now, int limit) {
    return ConnectorTenantScope.bypass(
        () ->
            mapper
                .selectList(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .in(ChannelConnectionEntity::getRuntimeStatus, "PENDING", "ERROR")
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getNextReconcileAt)
                                    .or()
                                    .le(ChannelConnectionEntity::getNextReconcileAt, now))
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getReconcileLeaseUntil)
                                    .or()
                                    .lt(ChannelConnectionEntity::getReconcileLeaseUntil, now))
                        .orderByAsc(ChannelConnectionEntity::getNextReconcileAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(row -> new ReconcileCandidate(row.getId(), row.getTenantId()))
                .toList());
  }

  public boolean reconcile(
      ReconcileCandidate candidate, String workerId, LocalDateTime now, Duration leaseDuration) {
    Duration lease =
        leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
            ? Duration.ofSeconds(30)
            : leaseDuration;
    int claimed =
        ConnectorTenantScope.bypass(
            () ->
                mapper.update(
                    null,
                    new LambdaUpdateWrapper<ChannelConnectionEntity>()
                        .set(ChannelConnectionEntity::getReconcileLeaseOwner, workerId)
                        .set(ChannelConnectionEntity::getReconcileLeaseUntil, now.plus(lease))
                        .eq(ChannelConnectionEntity::getTenantId, candidate.tenantId())
                        .eq(ChannelConnectionEntity::getId, candidate.id())
                        .in(ChannelConnectionEntity::getRuntimeStatus, "PENDING", "ERROR")
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getNextReconcileAt)
                                    .or()
                                    .le(ChannelConnectionEntity::getNextReconcileAt, now))
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getReconcileLeaseUntil)
                                    .or()
                                    .lt(ChannelConnectionEntity::getReconcileLeaseUntil, now))));
    if (claimed != 1) return false;
    ChannelConnectionEntity entity =
        ConnectorTenantScope.bypass(
            () ->
                mapper.selectOne(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(ChannelConnectionEntity::getTenantId, candidate.tenantId())
                        .eq(ChannelConnectionEntity::getId, candidate.id())
                        .last("LIMIT 1")));
    if (entity == null) return false;
    try {
      ChannelAccount account =
          runtimes
              .require(entity.getProvider(), entity.getRuntimeNodeId())
              .saveAccount(
                  new SaveChannelAccountRequest(
                      entity.getChannelId(),
                      entity.getRuntimeAccountId(),
                      entity.getName(),
                      "ACTIVE".equals(entity.getDesiredStatus()),
                      runtimeConfig(entity)));
      requireStableRuntimeAccount(entity, account);
      ConnectorTenantScope.bypass(
          () ->
              mapper.update(
                  null,
                  new LambdaUpdateWrapper<ChannelConnectionEntity>()
                      .set(
                          ChannelConnectionEntity::getRuntimeStatus,
                          account.connected()
                              ? "ONLINE"
                              : account.running() ? "RUNNING" : "STARTING")
                      .set(ChannelConnectionEntity::getRuntimeMetadataJson, metadataJson(account))
                      .set(ChannelConnectionEntity::getLastError, account.lastError())
                      .set(ChannelConnectionEntity::getReconcileAttempts, 0)
                      .set(ChannelConnectionEntity::getNextReconcileAt, null)
                      .set(ChannelConnectionEntity::getReconcileLeaseUntil, null)
                      .set(ChannelConnectionEntity::getReconcileLeaseOwner, null)
                      .eq(ChannelConnectionEntity::getTenantId, entity.getTenantId())
                      .eq(ChannelConnectionEntity::getId, entity.getId())
                      .eq(ChannelConnectionEntity::getReconcileLeaseOwner, workerId)));
      return true;
    } catch (RuntimeException failure) {
      int attempts =
          (entity.getReconcileAttempts() == null ? 0 : entity.getReconcileAttempts()) + 1;
      long delay = Math.min(300, 1L << Math.min(8, attempts));
      ConnectorTenantScope.bypass(
          () ->
              mapper.update(
                  null,
                  new LambdaUpdateWrapper<ChannelConnectionEntity>()
                      .set(ChannelConnectionEntity::getRuntimeStatus, "ERROR")
                      .set(ChannelConnectionEntity::getLastError, safeMessage(failure))
                      .set(ChannelConnectionEntity::getReconcileAttempts, attempts)
                      .set(
                          ChannelConnectionEntity::getNextReconcileAt,
                          LocalDateTime.now().plusSeconds(delay))
                      .set(ChannelConnectionEntity::getReconcileLeaseUntil, null)
                      .set(ChannelConnectionEntity::getReconcileLeaseOwner, null)
                      .eq(ChannelConnectionEntity::getTenantId, entity.getTenantId())
                      .eq(ChannelConnectionEntity::getId, entity.getId())
                      .eq(ChannelConnectionEntity::getReconcileLeaseOwner, workerId)));
      return false;
    }
  }

  /**
   * Selects stale active connections without issuing runtime calls while a tenant query is open.
   */
  public List<HealthCandidate> healthCandidates(LocalDateTime staleBefore, int limit) {
    return ConnectorTenantScope.bypass(
        () ->
            mapper
                .selectList(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(ChannelConnectionEntity::getDesiredStatus, "ACTIVE")
                        .notIn(ChannelConnectionEntity::getRuntimeStatus, "PENDING", "ERROR")
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getLastTestedAt)
                                    .or()
                                    .le(ChannelConnectionEntity::getLastTestedAt, staleBefore))
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getReconcileLeaseUntil)
                                    .or()
                                    .lt(
                                        ChannelConnectionEntity::getReconcileLeaseUntil,
                                        LocalDateTime.now()))
                        .orderByAsc(ChannelConnectionEntity::getLastTestedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(row -> new HealthCandidate(row.getId(), row.getTenantId()))
                .toList());
  }

  /**
   * Refreshes one runtime snapshot under a shared database lease, safe across embedded host nodes.
   */
  public boolean refreshHealth(
      HealthCandidate candidate, String workerId, LocalDateTime now, Duration leaseDuration) {
    Duration lease =
        leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
            ? Duration.ofSeconds(30)
            : leaseDuration;
    int claimed =
        ConnectorTenantScope.bypass(
            () ->
                mapper.update(
                    null,
                    new LambdaUpdateWrapper<ChannelConnectionEntity>()
                        .set(ChannelConnectionEntity::getReconcileLeaseOwner, workerId)
                        .set(ChannelConnectionEntity::getReconcileLeaseUntil, now.plus(lease))
                        .eq(ChannelConnectionEntity::getTenantId, candidate.tenantId())
                        .eq(ChannelConnectionEntity::getId, candidate.id())
                        .eq(ChannelConnectionEntity::getDesiredStatus, "ACTIVE")
                        .notIn(ChannelConnectionEntity::getRuntimeStatus, "PENDING", "ERROR")
                        .and(
                            q ->
                                q.isNull(ChannelConnectionEntity::getReconcileLeaseUntil)
                                    .or()
                                    .lt(ChannelConnectionEntity::getReconcileLeaseUntil, now))));
    if (claimed != 1) return false;
    ChannelConnectionEntity entity =
        ConnectorTenantScope.bypass(
            () ->
                mapper.selectOne(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(ChannelConnectionEntity::getTenantId, candidate.tenantId())
                        .eq(ChannelConnectionEntity::getId, candidate.id())
                        .last("LIMIT 1")));
    if (entity == null) return false;
    String status;
    String error;
    String metadata = entity.getRuntimeMetadataJson();
    try {
      ChannelAccount account =
          runtimes
              .require(entity.getProvider(), entity.getRuntimeNodeId())
              .testAccount(entity.getChannelId(), entity.getRuntimeAccountId());
      status = account.connected() ? "ONLINE" : account.running() ? "RUNNING" : "OFFLINE";
      error = account.lastError();
      metadata = metadataJson(account);
    } catch (RuntimeException failure) {
      status = "DEGRADED";
      error = safeMessage(failure);
    }
    String finalMetadata = metadata;
    String finalStatus = status;
    String finalError = error;
    int updated =
        ConnectorTenantScope.bypass(
            () ->
                mapper.update(
                    null,
                    new LambdaUpdateWrapper<ChannelConnectionEntity>()
                        .set(ChannelConnectionEntity::getRuntimeStatus, finalStatus)
                        .set(ChannelConnectionEntity::getRuntimeMetadataJson, finalMetadata)
                        .set(ChannelConnectionEntity::getLastError, finalError)
                        .set(ChannelConnectionEntity::getLastTestedAt, now)
                        .set(ChannelConnectionEntity::getReconcileLeaseUntil, null)
                        .set(ChannelConnectionEntity::getReconcileLeaseOwner, null)
                        .eq(ChannelConnectionEntity::getTenantId, entity.getTenantId())
                        .eq(ChannelConnectionEntity::getId, entity.getId())
                        .eq(ChannelConnectionEntity::getReconcileLeaseOwner, workerId)));
    return updated == 1 && !"DEGRADED".equals(status);
  }

  public List<View> list(String tenantId, String ownerId) {
    LambdaQueryWrapper<ChannelConnectionEntity> query =
        new LambdaQueryWrapper<ChannelConnectionEntity>()
            .eq(ChannelConnectionEntity::getTenantId, tenant(tenantId));
    if (ownerId != null && !ownerId.isBlank())
      query.eq(ChannelConnectionEntity::getOwnerId, ownerId.trim());
    return mapper.selectList(query).stream().map(ChannelConnectionService::view).toList();
  }

  public View get(String id, String tenantId) {
    return view(requireOwned(id, tenant(tenantId)));
  }

  public View test(String id, String tenantId) {
    ChannelConnectionEntity entity = requireOwned(id, tenant(tenantId));
    try {
      ChannelAccount account =
          runtimes
              .require(entity.getProvider(), entity.getRuntimeNodeId())
              .testAccount(entity.getChannelId(), entity.getRuntimeAccountId());
      entity.setRuntimeStatus(
          account.connected() ? "ONLINE" : account.running() ? "RUNNING" : "OFFLINE");
      entity.setRuntimeMetadataJson(metadataJson(account));
      entity.setLastError(account.lastError());
    } catch (RuntimeException ex) {
      entity.setRuntimeStatus("ERROR");
      entity.setLastError(safeMessage(ex));
    }
    entity.setLastTestedAt(LocalDateTime.now());
    updateOwned(entity);
    return view(entity);
  }

  public Route route(String provider, String channelId, String runtimeAccountId) {
    ChannelConnectionEntity entity = findRuntimeConnection(provider, channelId, runtimeAccountId);
    if (entity == null
        || !"ACTIVE".equals(entity.getDesiredStatus())
        || entity.getAgentId() == null
        || entity.getAgentId().isBlank()) return null;
    return new Route(
        entity.getId(), entity.getTenantId(), entity.getOwnerId(), entity.getAgentId());
  }

  public Ownership ownership(String provider, String channelId, String runtimeAccountId) {
    return ownership(provider, null, channelId, runtimeAccountId);
  }

  public Ownership ownership(
      String provider, String runtimeNodeId, String channelId, String runtimeAccountId) {
    ChannelConnectionEntity entity =
        findRuntimeConnection(provider, runtimeNodeId, channelId, runtimeAccountId);
    return entity == null
        ? null
        : new Ownership(
            entity.getId(), entity.getTenantId(), entity.getOwnerId(), entity.getRuntimeNodeId());
  }

  public RoutingConnection routingConnection(
      String provider, String channelId, String runtimeAccountId) {
    return routingConnection(provider, null, channelId, runtimeAccountId);
  }

  public RoutingConnection routingConnection(
      String provider, String runtimeNodeId, String channelId, String runtimeAccountId) {
    ChannelConnectionEntity entity =
        findRuntimeConnection(provider, runtimeNodeId, channelId, runtimeAccountId);
    return entity == null
        ? null
        : new RoutingConnection(
            entity.getId(),
            entity.getTenantId(),
            entity.getOwnerId(),
            trimToNull(entity.getAgentId()),
            trimToNull(entity.getAgentVersionId()),
            "ACTIVE".equals(entity.getDesiredStatus()));
  }

  private ChannelConnectionEntity findRuntimeConnection(
      String provider, String channelId, String runtimeAccountId) {
    return findRuntimeConnection(provider, null, channelId, runtimeAccountId);
  }

  private ChannelConnectionEntity findRuntimeConnection(
      String provider, String runtimeNodeId, String channelId, String runtimeAccountId) {
    List<ChannelConnectionEntity> matches =
        ConnectorTenantScope.bypass(
            () ->
                mapper.selectList(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(ChannelConnectionEntity::getProvider, provider)
                        .eq(ChannelConnectionEntity::getChannelId, channelId)
                        .eq(ChannelConnectionEntity::getRuntimeAccountId, runtimeAccountId)
                        .eq(
                            runtimeNodeId != null && !runtimeNodeId.isBlank(),
                            ChannelConnectionEntity::getRuntimeNodeId,
                            runtimeNodeId)
                        .last("LIMIT 2")));
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  public void delete(String id, String tenantId) {
    ChannelConnectionEntity entity = requireOwned(id, tenant(tenantId));
    runtimes
        .require(entity.getProvider(), entity.getRuntimeNodeId())
        .deleteAccount(entity.getChannelId(), entity.getRuntimeAccountId());
    mapper.delete(
        new LambdaQueryWrapper<ChannelConnectionEntity>()
            .eq(ChannelConnectionEntity::getTenantId, entity.getTenantId())
            .eq(ChannelConnectionEntity::getId, entity.getId()));
  }

  private Map<String, Object> runtimeConfig(ChannelConnectionEntity entity) {
    Map<String, Object> values =
        new LinkedHashMap<>(codec.decode(entity.getTenantId(), entity.getEncryptedConfig()));
    values.putAll(codec.decode(entity.getTenantId(), entity.getEncryptedCredentials()));
    return values;
  }

  private ChannelConnectionEntity requireOwned(String id, String tenantId) {
    ChannelConnectionEntity entity =
        mapper.selectOne(
            new LambdaQueryWrapper<ChannelConnectionEntity>()
                .eq(ChannelConnectionEntity::getTenantId, tenantId)
                .eq(ChannelConnectionEntity::getId, id)
                .last("LIMIT 1"));
    if (entity == null) {
      throw new ConnectorException("channel_connection_not_found", "Channel connection not found");
    }
    return entity;
  }

  private void updateOwned(ChannelConnectionEntity entity) {
    mapper.update(
        entity,
        new LambdaUpdateWrapper<ChannelConnectionEntity>()
            .eq(ChannelConnectionEntity::getTenantId, entity.getTenantId())
            .eq(ChannelConnectionEntity::getId, entity.getId()));
  }

  private static View view(ChannelConnectionEntity entity) {
    return new View(
        entity.getId(),
        entity.getTenantId(),
        entity.getOwnerType(),
        entity.getOwnerId(),
        entity.getProvider(),
        entity.getChannelId(),
        entity.getName(),
        entity.getDesiredStatus(),
        entity.getRuntimeStatus(),
        entity.getRuntimeAccountId(),
        entity.getAgentId(),
        entity.getAgentVersionId(),
        entity.getRuntimeNodeId(),
        JsonUtils.parseMap(entity.getRuntimeMetadataJson()),
        entity.getEncryptedCredentials() != null,
        entity.getLastError(),
        entity.getLastTestedAt(),
        entity.getConfigVersion() == null ? 0 : entity.getConfigVersion());
  }

  private static String metadataJson(ChannelAccount account) {
    return account.metadata() == null || account.metadata().isEmpty()
        ? null
        : JsonUtils.toJson(account.metadata());
  }

  private static String tenant(String value) {
    return defaulted(value, "default");
  }

  private static String defaulted(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String selectNode(String provider, String requested) {
    if (requested != null && !requested.isBlank())
      return runtimes.require(provider, requested.trim()).nodeId();
    List<String> nodes = runtimes.nodeIds(provider);
    if (nodes.isEmpty())
      throw new ConnectorException(
          "channel_runtime_not_found", "Channel runtime provider not found: " + provider);
    return nodes.getFirst();
  }

  private static String safeMessage(RuntimeException ex) {
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }

  private static void requireStableRuntimeAccount(
      ChannelConnectionEntity entity, ChannelAccount account) {
    if (account.accountId() != null
        && !account.accountId().isBlank()
        && !entity.getRuntimeAccountId().equals(account.accountId())) {
      throw new ConnectorException(
          "channel_runtime_account_remapped",
          "Runtime changed the reserved account id; reconciliation was stopped");
    }
  }
}
