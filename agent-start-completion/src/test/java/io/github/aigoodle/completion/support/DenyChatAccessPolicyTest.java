package io.github.aigoodle.completion.support;

import io.github.aigoodle.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DenyChatAccessPolicyTest {

    private final ChatAccessPolicy policy = new DenyChatAccessPolicy();

    @Test
    void rejectsUnconfiguredDebugAccess() {
        assertThatThrownBy(() -> policy.authorizeDebug("app-1", null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ChatAccessPolicy");
    }
}
