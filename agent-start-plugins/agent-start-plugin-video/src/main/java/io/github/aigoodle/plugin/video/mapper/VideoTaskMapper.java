package io.github.aigoodle.plugin.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.plugin.video.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** Atomic task transitions. Tenant and lease conditions are part of the update itself. */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> {
    @Update("UPDATE plugin_video_task SET vendor_task_id=#{vendorId},status='QUEUED',next_poll_at=#{now},updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND id=#{id} AND status='SUBMITTING'")
    int accept(@Param("tenant") String tenant, @Param("id") String id, @Param("vendorId") String vendorId, @Param("now") Instant now);

    @Update("UPDATE plugin_video_task SET status='UNKNOWN',last_error='submission_outcome_unknown',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND id=#{id} AND status='SUBMITTING'")
    int markUnknown(@Param("tenant") String tenant, @Param("id") String id);

    @Update("UPDATE plugin_video_task SET cancel_requested=1,next_poll_at=#{now},updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND id=#{id} AND status IN ('QUEUED','RUNNING')")
    int requestCancel(@Param("tenant") String tenant, @Param("id") String id, @Param("now") Instant now);

    /** Scheduler-only discovery across tenants; no caller-supplied tenant is discarded. */
    @Update("UPDATE plugin_video_task SET status='UNKNOWN',last_error='submission_outcome_unknown',updated_at=CURRENT_TIMESTAMP WHERE status='SUBMITTING' AND next_poll_at<#{now}")
    int expireSubmissions(@Param("now") Instant now);

    @Select("SELECT * FROM plugin_video_task WHERE status IN ('QUEUED','RUNNING') AND next_poll_at<=#{now} AND (lease_until IS NULL OR lease_until<#{now}) ORDER BY next_poll_at LIMIT #{limit}")
    List<VideoTaskEntity> selectDue(@Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE plugin_video_task SET lease_token=#{token},lease_until=#{until} WHERE tenant_id=#{tenant} AND id=#{id} AND status IN ('QUEUED','RUNNING') AND next_poll_at<=#{now} AND (lease_until IS NULL OR lease_until<#{now})")
    int claim(@Param("tenant") String tenant, @Param("id") String id, @Param("token") String token, @Param("now") Instant now, @Param("until") Instant until);

    @Update("UPDATE plugin_video_task SET status=#{status},result_json=#{result},last_error=#{error},attempts=attempts+1,next_poll_at=#{nextPoll},lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND id=#{id} AND lease_token=#{token}")
    int finish(@Param("tenant") String tenant, @Param("id") String id, @Param("token") String token,
               @Param("status") String status, @Param("result") String result, @Param("error") String error, @Param("nextPoll") Instant nextPoll);
}
