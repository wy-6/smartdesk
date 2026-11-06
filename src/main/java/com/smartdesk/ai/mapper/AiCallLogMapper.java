package com.smartdesk.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.ai.entity.AiCallLog;

/**
 * AI 调用记录 Mapper（Week3 / Day19）。
 *
 * <p>只做单表 CRUD，继承 {@link BaseMapper} 即可。
 * 条件查询用 {@code LambdaQueryWrapper} 在 Service 层拼，
 * 好处是字段名写错会编译报错，而不是等到运行期 SQL 报 {@code Unknown column}。
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

}
