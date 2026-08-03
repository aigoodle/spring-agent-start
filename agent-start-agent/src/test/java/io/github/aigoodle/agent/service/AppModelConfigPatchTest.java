package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppModelConfigPatchTest {

    @Test
    void preservesOmittedFieldsAndAllowsExplicitClearing() {
        AppModelConfig existing = new AppModelConfig();
        existing.setModelProvider("qwen");
        existing.setPrePrompt("Existing prompt");
        existing.setMemoryWindow(20);
        AppModelConfig patch = new AppModelConfig();
        patch.setPrePrompt("");
        patch.setMemoryWindow(40);

        AppModelConfigPatch.apply(existing, patch);

        assertThat(existing.getModelProvider()).isEqualTo("qwen");
        assertThat(existing.getPrePrompt()).isEmpty();
        assertThat(existing.getMemoryWindow()).isEqualTo(40);
    }
}
