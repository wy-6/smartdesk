package com.smartdesk.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.ai.entity.AiToolCallLog;

/**
 * 工具调用审计 Mapper（Week4 / Day26）。
 *
 * <p>没有自定义 SQL：所有查询都能用 {@code LambdaQueryWrapper} 表达，
 * 而 wrapper 会强制你写字段引用而不是字符串列名，改字段名时编译期就能发现。
 */
@Mapper
public interface AiToolCallLogMapper extends BaseMapper<AiToolCallLog> {

}
