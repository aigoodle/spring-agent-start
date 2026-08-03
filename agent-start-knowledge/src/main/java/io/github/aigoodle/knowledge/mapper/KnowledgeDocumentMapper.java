package io.github.aigoodle.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
    @Select("SELECT parsed_document_json FROM documents WHERE id = #{documentId}")
    String selectParsedDocumentJson(String documentId);

    @Select("SELECT source_data_base64 FROM documents WHERE id = #{documentId}")
    String selectSourceDataBase64(String documentId);
}
