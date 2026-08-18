package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.installation.ConnectorInstallationService;
import io.github.aigoodle.connector.installation.ConnectorInstallationService.InstallationView;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnBean(ConnectorInstallationService.class)
@RequestMapping("/connector-installations")
public class ConnectorInstallationController {
    private final ConnectorInstallationService installations;

    public ConnectorInstallationController(ConnectorInstallationService installations) {
        this.installations = installations;
    }

    @GetMapping
    public ApiResponse<List<InstallationView>> list(
            @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(installations.list(tenantId));
    }

    @PostMapping("/synchronize")
    public ApiResponse<List<InstallationView>> synchronize(
            @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(installations.synchronize(tenantId));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<InstallationView> enable(@PathVariable String id,
                                                @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(installations.setEnabled(id, tenantId, true));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<InstallationView> disable(@PathVariable String id,
                                                 @RequestParam(defaultValue = "default") String tenantId) {
        return ApiResponse.ok(installations.setEnabled(id, tenantId, false));
    }
}
