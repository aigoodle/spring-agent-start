package io.github.aigoodle.trigger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TriggerMapper extends BaseMapper<TriggerEntity> {
}
