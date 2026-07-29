package io.github.aigoodle.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.model.entity.TenantDefaultModelEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantDefaultModelMapper extends BaseMapper<TenantDefaultModelEntity> {
}
