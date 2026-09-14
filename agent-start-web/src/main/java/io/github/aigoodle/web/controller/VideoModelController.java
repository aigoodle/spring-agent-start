package io.github.aigoodle.web.controller;

import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.model.video.VideoModelService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@ConditionalOnBean(VideoModelService.class)
@RequestMapping("/video-models")
public class VideoModelController {
    private final VideoModelService videos;
    public VideoModelController(VideoModelService videos) { this.videos = videos; }
    @PostMapping("/capabilities")
    public Map<String, Object> capabilities(@RequestBody Map<String, Object> selection) {
        return videos.capabilities(UserContextHolder.currentTenantId(), selection);
    }
}
