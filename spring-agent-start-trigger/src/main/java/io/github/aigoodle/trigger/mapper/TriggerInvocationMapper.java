package io.github.aigoodle.trigger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TriggerInvocationMapper extends BaseMapper<TriggerInvocationEntity> {
}
