package io.github.aigoodle.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PredefinedModelMapper extends BaseMapper<PredefinedModelEntity> {
}
