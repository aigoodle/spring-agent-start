package io.github.aigoodle.plugin.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.plugin.video.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for durable video tasks. */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> { }
