package com.smartdesk.knowledge.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.knowledge.domain.KnowledgeDocument;

/**
 * 知识库文档 Mapper（Week5）。
 *
 * <p>所有查询都要带 {@code owner_id}。本 Mapper 不提供任何"按 id 查"的便捷方法，
 * 就是为了逼调用方显式写出 owner 条件 —— 第 1 周的规矩：数据边界只来自登录态。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

}
