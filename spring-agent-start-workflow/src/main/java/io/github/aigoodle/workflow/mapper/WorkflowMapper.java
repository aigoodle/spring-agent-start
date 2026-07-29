package io.github.aigoodle.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowEntity> {
}
