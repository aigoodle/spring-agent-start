package io.github.aigoodle.workflow.service;

import io.github.aigoodle.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContractExtractorTest {

    @Test
    void persistsStartEndAndEveryNodeOutputDefinition() {
        String contractJson = WorkflowContractExtractor.extract(JsonUtils.readTree("""
                {
                  "nodes": [
                    {"id":"start","type":"START","data":{"variables":[{"name":"topic","type":"string"}],"output":[]}},
                    {"id":"llm","type":"LLM","data":{"output":[{"name":"text","type":"string"}]}},
                    {"id":"end","type":"END","data":{"output":[{"name":"answer","type":"string"}]}}
                  ],
                  "edges": []
                }
                """));

        Map<String, Object> contract = JsonUtils.parseMap(contractJson);
        assertThat((List<?>) contract.get("inputs")).extracting(String::valueOf)
                .anyMatch(value -> value.contains("topic"));
        assertThat((List<?>) contract.get("outputs")).extracting(String::valueOf)
                .anyMatch(value -> value.contains("answer"));
        assertThat((List<?>) contract.get("nodeOutputs")).hasSize(2);
    }
}
