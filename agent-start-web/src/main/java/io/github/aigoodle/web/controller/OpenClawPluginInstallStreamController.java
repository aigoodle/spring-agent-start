package io.github.aigoodle.web.controller;

import io.github.aigoodle.connector.openclaw.OpenClawDtos;
import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.support.ChannelRuntimeAdministrationPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** MVC SSE facade for the blocking OpenClaw plugin installation command. */
@RestController
@ConditionalOnClass(SseEmitter.class)
@ConditionalOnBean(OpenClawController.class)
@RequestMapping("/openclaw/plugins")
public class OpenClawPluginInstallStreamController {
    private final OpenClawController openClaw;
    private final ChannelRuntimeAdministrationPolicy administration;

    public OpenClawPluginInstallStreamController(OpenClawController openClaw,
                                                  ChannelRuntimeAdministrationPolicy administration) {
        this.openClaw = openClaw;
        this.administration = administration;
    }

    @PostMapping(value = "/install/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter install(@RequestBody OpenClawDtos.InstallRequest request) {
        administration.requireRuntimeAdministrator();
        return SseEmitterBridge.stream(emit -> {
            emit.event("progress", progress(5, "VALIDATING", "正在校验插件来源"));
            validate(request);
            emit.event("progress", progress(20, "CONNECTING", "正在连接 OpenClaw 运行时"));
            emit.event("progress", progress(35, "INSTALLING", "正在下载并安装插件"));
            OpenClawDtos.PluginInfo installed = openClaw.installPlugin(request);
            emit.event("progress", progress(85, "REFRESHING", "安装完成，正在刷新插件与工具目录"));
            emit.event("progress", progress(100, "COMPLETED", "插件安装成功"));
            emit.event("result", installed);
        });
    }

    private static Map<String, Object> progress(int percent, String stage, String message) {
        return Map.of("percent", percent, "stage", stage, "message", message);
    }

    private static void validate(OpenClawDtos.InstallRequest request) {
        if (request == null || request.source() == null || request.source().isBlank())
            throw new IllegalArgumentException("插件来源不能为空");
        String sourceType = request.sourceType() == null ? "npm" : request.sourceType().trim().toLowerCase();
        if (!sourceType.equals("npm") && !sourceType.equals("clawhub"))
            throw new IllegalArgumentException("插件来源仅支持 npm 或 ClawHub");
    }
}
