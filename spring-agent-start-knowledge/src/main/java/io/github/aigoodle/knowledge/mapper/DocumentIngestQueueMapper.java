package io.github.aigoodle.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.knowledge.async.DocumentIngestQueueEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentIngestQueueMapper extends BaseMapper<DocumentIngestQueueEntity> {
}
