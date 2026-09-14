package io.github.aigoodle.plugin.seedance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.plugin.seedance.entity.SeedanceSubmissionEntity;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SeedanceSubmissionMapper extends BaseMapper<SeedanceSubmissionEntity> {
    @Update("UPDATE plugin_seedance_submission SET task_id=#{taskId} WHERE invocation_key=#{key} AND task_id IS NULL")
    int accept(@Param("key") String key, @Param("taskId") String taskId);
}
