package com.smartdesk.knowledge.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.knowledge.domain.KnowledgeChunk;

/**
 * 知识库切片 Mapper（Week5）。
 *
 * <p>检索时用 {@code LambdaQueryWrapper} 拼出
 * {@code WHERE owner_id = ? AND document_id IN (...)}，
 * owner 过滤<b>在 SQL 里完成</b>，不是查出来在内存里筛。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

}
