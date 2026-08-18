package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.connection.ConnectorConnectionService;
import io.github.aigoodle.connector.connection.ConnectorConnectionService.ConnectionView;
import io.github.aigoodle.connector.connection.ConnectorConnectionService.SaveConnectionRequest;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tenant-scoped connection management. Secret values are accepted but never returned. */
@RestController
@ConditionalOnBean(ConnectorConnectionService.class)
@RequestMapping("/connector-connections")
public class ConnectorConnectionController {
    private final ConnectorConnectionService connections;

    public ConnectorConnectionController(ConnectorConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping
    public ApiResponse<List<ConnectionView>> list(
            @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(connections.list(tenantId));
    }

    @PostMapping
    public ApiResponse<ConnectionView> save(@RequestBody SaveConnectionRequest request) {
        return ApiResponse.ok(connections.save(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id,
                                    @RequestParam(defaultValue = "default") String tenantId) {
        connections.delete(id, tenantId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ConnectorConnectionService.ConnectionTestResult> test(
            @PathVariable String id,
            @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(connections.test(id, tenantId));
    }
}
