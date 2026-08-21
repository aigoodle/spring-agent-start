package io.github.aigoodle.connector.channel;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.github.aigoodle.connector.config.ConnectorProperties;
import io.github.aigoodle.connector.persistence.ChannelEventEntity;
import io.github.aigoodle.connector.persistence.ChannelEventMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelDeadLetterServiceTest {
    @BeforeAll static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "dead-letter-test"),
                ChannelEventEntity.class);
    }

    @Test
    void replayIsTenantFencedAndOnlyResetsStillTerminalOutboundRows() {
        ChannelEventMapper mapper = mock(ChannelEventMapper.class);
        ConnectorProperties properties = new ConnectorProperties();
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId("event-1"); event.setTenantId("tenant-a"); event.setDirection("OUTBOUND");
        event.setStatus("FAILED"); event.setAttempts(properties.getChannelOutboxMaxAttempts());
        when(mapper.selectList(any())).thenReturn(List.of(event));
        when(mapper.update(isNull(), any())).thenReturn(1);
        ChannelEventLogService service = new ChannelEventLogService(mapper, mock(ChannelConnectionService.class),
                mock(ChannelInboundDispatcher.class), new ChannelRuntimeRegistry(List.of()), properties);

        var result = service.replayDeadLetters("tenant-a", List.of("event-1", "event-1", " "));

        assertThat(result).isEqualTo(new ChannelEventLogService.DeadLetterReplayResult(1, 1, 1));
        @SuppressWarnings("rawtypes") ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "direction", "status", "attempts", "id");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains("tenant-a", "OUTBOUND", "FAILED", "event-1");
        @SuppressWarnings("rawtypes") ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("tenant_id", "direction", "status", "attempts", "id");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("tenant-a", "OUTBOUND", "FAILED", "event-1");
    }
}
