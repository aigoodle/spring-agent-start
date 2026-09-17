package io.github.aigoodle.connectors.nativebot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.persistence.ChannelConnectionEntity;
import io.github.aigoodle.connector.persistence.ChannelConnectionMapper;
import io.github.aigoodle.connector.persistence.ConnectorTenantScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

final class JdbcNativeAccountStore implements NativeAccountStore {
  private final ChannelConnectionMapper mapper;
  private final ConnectorSecretCodec secrets;

  JdbcNativeAccountStore(ChannelConnectionMapper mapper, ConnectorSecretCodec secrets) {
    this.mapper = mapper;
    this.secrets = secrets;
  }

  @Override
  public Optional<Saved> find(String channelId, String accountId) {
    ChannelConnectionEntity row =
        ConnectorTenantScope.bypass(
            () ->
                mapper.selectOne(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(
                            ChannelConnectionEntity::getProvider,
                            NativeChannelRuntimeProvider.PROVIDER)
                        .eq(ChannelConnectionEntity::getChannelId, channelId)
                        .eq(ChannelConnectionEntity::getRuntimeAccountId, accountId)
                        .last("LIMIT 1")));
    if (row == null) return Optional.empty();
    return Optional.of(toSaved(row));
  }

  @Override
  public List<SavedAccount> findEnabled() {
    List<ChannelConnectionEntity> rows =
        ConnectorTenantScope.bypass(
            () ->
                mapper.selectList(
                    new LambdaQueryWrapper<ChannelConnectionEntity>()
                        .eq(ChannelConnectionEntity::getProvider, NativeChannelRuntimeProvider.PROVIDER)
                        .eq(ChannelConnectionEntity::getDesiredStatus, "ACTIVE")));
    return rows.stream()
        .filter(row -> row.getChannelId() != null && row.getRuntimeAccountId() != null)
        .map(
            row ->
                new SavedAccount(
                    row.getChannelId(), row.getRuntimeAccountId(), toSaved(row)))
        .toList();
  }

  private Saved toSaved(ChannelConnectionEntity row) {
    var values = new LinkedHashMap<>(secrets.decode(row.getTenantId(), row.getEncryptedConfig()));
    values.putAll(secrets.decode(row.getTenantId(), row.getEncryptedCredentials()));
    values.put("accountId", row.getRuntimeAccountId());
    return new Saved(row.getName(), "ACTIVE".equals(row.getDesiredStatus()), values);
  }
}
