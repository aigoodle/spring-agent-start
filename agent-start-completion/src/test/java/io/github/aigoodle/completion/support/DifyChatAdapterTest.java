package io.github.aigoodle.completion.support;

import io.github.aigoodle.completion.dto.dify.DifyChatMessagesRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DifyChatAdapterTest {

    @Test
    void carriesAgentDeadlineIntoInternalRequest() {
        DifyChatMessagesRequest source = new DifyChatMessagesRequest();
        source.setQuery("hello");
        source.setTimeoutMillis(12_345L);

        var internal = DifyChatAdapter.toInternalRequest(source);

        assertThat(internal.getTimeoutMillis()).isEqualTo(12_345L);
        assertThat(internal.lastUserMessage()).isEqualTo("hello");
    }
}
