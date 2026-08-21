package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.installation.ConnectorInstallationService;
import io.github.aigoodle.connector.installation.ConnectorInstallationService.InstallationView;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

@RestController
@ConditionalOnBean(ConnectorInstallationService.class)
@RequestMapping("/connector-installations")
public class ConnectorInstallationController {
    private final ConnectorInstallationService installations;

    public ConnectorInstallationController(ConnectorInstallationService installations) {
        this.installations = installations;
    }

    @GetMapping
    public ApiResponse<List<InstallationView>> list() {
        return ApiResponse.ok(installations.list(currentTenantId()));
    }

    @PostMapping("/synchronize")
    public ApiResponse<List<InstallationView>> synchronize() {
        return ApiResponse.ok(installations.synchronize(currentTenantId()));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<InstallationView> enable(@PathVariable String id) {
        return ApiResponse.ok(installations.setEnabled(id, currentTenantId(), true));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<InstallationView> disable(@PathVariable String id) {
        return ApiResponse.ok(installations.setEnabled(id, currentTenantId(), false));
    }
}
