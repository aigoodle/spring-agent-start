package io.github.aigoodle.trigger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TriggerMapper extends BaseMapper<TriggerEntity> {

    @Update("""
            UPDATE goodle_app_triggers
               SET lock_owner = #{owner}, lock_until = #{lockUntil}, updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND enabled = TRUE
               AND type = 'CRON'
               AND next_fire_at IS NOT NULL
               AND next_fire_at <= #{now}
               AND (lock_until IS NULL OR lock_until < #{now})
            """)
    int tryClaim(@Param("id") String id,
                 @Param("owner") String owner,
                 @Param("now") LocalDateTime now,
                 @Param("lockUntil") LocalDateTime lockUntil);
}
