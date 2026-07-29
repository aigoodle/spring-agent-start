package io.github.aigoodle.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.model.entity.PromptTemplateEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateEntity> {
}
