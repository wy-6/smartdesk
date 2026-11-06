package com.smartdesk.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.ai.entity.ChatMessage;

/** 会话消息 Mapper（Week4 / Day27）。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}
