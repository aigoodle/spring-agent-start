package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfigEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppModelConfigPatchTest {

    @Test
    void preservesOmittedFieldsAndAllowsExplicitClearing() {
        AppModelConfigEntity existing = new AppModelConfigEntity();
        existing.setModelProvider("qwen");
        existing.setPrePrompt("Existing prompt");
        existing.setMemoryWindow(20);
        AppModelConfigEntity patch = new AppModelConfigEntity();
        patch.setPrePrompt("");
        patch.setMemoryWindow(40);

        AppModelConfigPatch.apply(existing, patch);

        assertThat(existing.getModelProvider()).isEqualTo("qwen");
        assertThat(existing.getPrePrompt()).isEmpty();
        assertThat(existing.getMemoryWindow()).isEqualTo(40);
    }
}
