package io.github.aigoodle.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.model.entity.ModelEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMapper extends BaseMapper<ModelEntity> {
}
