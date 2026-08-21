package io.github.aigoodle.connector.openclaw;

import io.github.aigoodle.connector.channel.ChannelAccount;
import io.github.aigoodle.connector.channel.ChannelDefinition;
import io.github.aigoodle.connector.channel.ChannelRuntimeProvider;
import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import io.github.aigoodle.connector.channel.ChannelSendResult;
import io.github.aigoodle.connector.channel.SaveChannelAccountRequest;

import java.util.List;
import java.util.Map;

/** Adapts OpenClaw channel accounts to the provider-neutral channel runtime SPI. */
public class OpenClawChannelRuntimeProvider implements ChannelRuntimeProvider {
    private static final String PROVIDER = "openclaw";
    private final OpenClawGatewayClient client;

    public OpenClawChannelRuntimeProvider(OpenClawGatewayClient client) {
        this.client = client;
    }

    @Override public String type() { return PROVIDER; }

    @Override
    public List<ChannelDefinition> discoverChannels() {
        return client.channels().stream().map(channel -> new ChannelDefinition(PROVIDER,
                channel.id(), channel.label(), channel.description(), channel.version(), channel.installed(),
                channel.enabled(), channel.runtimeStatus(), channel.credentialSchema(), channel.configSchema(),
                safe(channel.uiSchema()), safe(channel.capabilities()), safe(channel.metadata()))).toList();
    }

    @Override
    public List<ChannelAccount> accounts(String channelId) {
        return client.channelAccounts(channelId).stream().map(this::map).toList();
    }

    @Override
    public ChannelAccount saveAccount(SaveChannelAccountRequest request) {
        OpenClawDtos.SaveChannelAccountRequest body = new OpenClawDtos.SaveChannelAccountRequest(
                request.name(), request.enabled(), request.configuration());
        return map(client.saveChannelAccount(request.channelId(), request.accountId(), body));
    }

    @Override
    public ChannelAccount testAccount(String channelId, String accountId) {
        return map(client.testChannelAccount(channelId, accountId));
    }

    @Override
    public void deleteAccount(String channelId, String accountId) {
        client.deleteChannelAccount(channelId, accountId);
    }

    @Override public void send(ChannelOutboundMessage message) {
        sendWithResult(message);
    }

    @Override public ChannelSendResult sendWithResult(ChannelOutboundMessage message) {
        Map<String, Object> result = client.sendChannelMessageWithResult(message);
        Object id = findMessageId(result);
        return new ChannelSendResult(id == null ? null : String.valueOf(id), result);
    }

    private static Object findMessageId(Map<String, Object> value) {
        for (String key : List.of("messageId", "message_id", "id")) {
            Object candidate = value.get(key);
            if (candidate != null && !(candidate instanceof Map<?, ?>)) return candidate;
        }
        for (Object nested : value.values()) {
            if (nested instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked") Map<String, Object> child = (Map<String, Object>) map;
                Object candidate = findMessageId(child);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private ChannelAccount map(OpenClawDtos.ChannelAccountInfo account) {
        return new ChannelAccount(PROVIDER, account.channelId(), account.accountId(), account.name(),
                account.enabled(), account.configured(), account.running(), account.connected(),
                account.lastConnectedAt(), account.lastError(), safe(account.metadata()));
    }

    private static Map<String, Object> safe(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

}
