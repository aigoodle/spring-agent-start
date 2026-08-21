package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.execution.ConnectorExecutionQueryService;
import io.github.aigoodle.connector.persistence.ConnectorExecutionEntity;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

@RestController
@ConditionalOnBean(ConnectorExecutionQueryService.class)
@RequestMapping("/connector-executions")
public class ConnectorExecutionController {
    private final ConnectorExecutionQueryService executions;
    public ConnectorExecutionController(ConnectorExecutionQueryService executions) { this.executions = executions; }

    @GetMapping
    public ApiResponse<List<ConnectorExecutionEntity>> list(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String connectorId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(executions.list(currentTenantId(), provider, connectorId, status, limit));
    }
}
