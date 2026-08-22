package io.github.aigoodle.web.service;

import io.github.aigoodle.connector.channel.ChannelConnectionService;
import io.github.aigoodle.connector.channel.ChannelOutboundMessage;
import io.github.aigoodle.connector.channel.ChannelRuntimeRegistry;
import io.github.aigoodle.workflow.entity.HumanInteractionEntity;
import io.github.aigoodle.workflow.interaction.HumanInteractionNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Sends the durable human-interaction prompt through a configured robot account. */
@Component
@ConditionalOnBean({ChannelConnectionService.class, ChannelRuntimeRegistry.class})
public class ChannelHumanInteractionNotifier implements HumanInteractionNotifier {
    private final ChannelConnectionService connections;
    private final ChannelRuntimeRegistry runtimes;

    public ChannelHumanInteractionNotifier(ChannelConnectionService connections, ChannelRuntimeRegistry runtimes) {
        this.connections = connections; this.runtimes = runtimes;
    }

    @Override
    public String notify(HumanInteractionEntity interaction) {
        var connection = connections.get(interaction.getChannelConnectionId(), interaction.getTenantId());
        if (!"ACTIVE".equals(connection.desiredStatus())) throw new IllegalStateException("notification channel is disabled");
        String prompt = interaction.getDescription() == null || interaction.getDescription().isBlank()
                ? interaction.getTitle() : interaction.getDescription();
        if (prompt == null || prompt.isBlank()) prompt = "请处理此人工介入任务";
        String content = prompt.trim() + "\n\n回复时请保留任务短码：#" + interaction.getShortCode();
        var sent = runtimes.require(connection.provider(), connection.runtimeNodeId()).sendWithResult(
                new ChannelOutboundMessage(connection.channelId(), connection.runtimeAccountId(),
                        interaction.getChannelTarget(), interaction.getChannelConversationId(), content, "TEXT",
                        List.of(), Map.of(), Map.of("idempotencyKey", "human-interaction:" + interaction.getId(),
                        "interactionId", interaction.getId(), "shortCode", interaction.getShortCode())));
        return sent.platformMessageId();
    }
}
