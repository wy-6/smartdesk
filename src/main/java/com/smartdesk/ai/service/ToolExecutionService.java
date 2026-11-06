package com.smartdesk.ai.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdesk.ai.entity.AiToolCallLog;
import com.smartdesk.ai.mapper.AiToolCallLogMapper;
import com.smartdesk.ai.tool.AgentToolContext;
import com.smartdesk.ai.tool.ToolExecutionResult;
import com.smartdesk.ai.tool.ToolRegistry;

/**
 * 工具执行服务（Week4 / Day24-26）。
 *
 * <p><b>它包在 {@link ToolRegistry} 外面，只做横切的三件事：</b>
 * 打指标、写审计、把执行委托出去。工具的技术性执行（参数解析、反射调用）
 * 留在 Registry 里，业务规则留在 {@code TaskTools} 里 —— 三层各管一件事。
 *
 * <p><b>为什么审计写失败不能影响主流程？</b>
 * 和第 3 周的调用日志同理：查询任务已经成功了，因为"审计表插入失败"
 * 给用户返回 500，是本末倒置。<b>审计可以降级，数据隔离不能降级。</b>
 */
@Service
public class ToolExecutionService {

	private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

	private static final int MAX_ARGUMENTS = 2000;

	private static final int MAX_SUMMARY = 500;

	private final ToolRegistry registry;

	private final AiToolCallLogMapper mapper;

	private final AiMetrics metrics;

	public ToolExecutionService(ToolRegistry registry, AiToolCallLogMapper mapper, AiMetrics metrics) {
		this.registry = registry;
		this.mapper = mapper;
		this.metrics = metrics;
	}

	/** 执行一次工具调用，附带指标与审计。 */
	public ToolExecutionResult execute(String toolName, String arguments, AgentToolContext context) {
		ToolExecutionResult result = registry.execute(toolName, arguments, context);
		metrics.recordToolCall(toolName, result.ok(), result.latencyMs());
		if (result.privilegeStripped()) {
			metrics.recordPrivilegeStripped();
		}
		audit(context, toolName, arguments, result);
		return result;
	}

	/** 分页查询当前用户的工具调用记录。强制带 ownerId。 */
	public List<AiToolCallLog> page(Long ownerId, int pageNum, int pageSize) {
		Page<AiToolCallLog> page = new Page<>(Math.max(1, pageNum), Math.min(100, Math.max(1, pageSize)));
		LambdaQueryWrapper<AiToolCallLog> query = new LambdaQueryWrapper<AiToolCallLog>()
				.eq(AiToolCallLog::getOwnerId, ownerId)
				.orderByDesc(AiToolCallLog::getCreatedAt)
				.orderByDesc(AiToolCallLog::getId);
		return mapper.selectPage(page, query).getRecords();
	}

	private void audit(AgentToolContext context, String toolName, String arguments, ToolExecutionResult result) {
		try {
			AiToolCallLog entity = new AiToolCallLog();
			entity.setOwnerId(context.userId());
			entity.setTraceId(context.traceId());
			entity.setSessionId(context.sessionId());
			entity.setToolName(toolName);
			entity.setArguments(truncate(arguments, MAX_ARGUMENTS));
			entity.setSuccess(result.ok());
			entity.setErrorCode(result.errorCode());
			entity.setResultSummary(truncate(result.payload(), MAX_SUMMARY));
			entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, result.latencyMs()));
			entity.setPrivilegeStripped(result.privilegeStripped());
			mapper.insert(entity);
		}
		catch (RuntimeException ex) {
			log.warn("写入工具审计失败 traceId={} tool={}: {}", context.traceId(), toolName, ex.getMessage());
		}
	}

	/** 脱敏 + 截断：去换行（防日志注入）、限长（防超列宽）。 */
	private static String truncate(String text, int max) {
		if (text == null) {
			return null;
		}
		String singleLine = text.replaceAll("[\\r\\n]+", " ").strip();
		return singleLine.length() > max ? singleLine.substring(0, max) + "…" : singleLine;
	}

}
