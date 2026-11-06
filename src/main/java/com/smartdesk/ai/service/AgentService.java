package com.smartdesk.ai.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Service;

import com.smartdesk.ai.dto.AgentResult;
import com.smartdesk.ai.model.PendingConfirmation;
import com.smartdesk.common.BusinessException;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

/**
 * Agent 门面（Week4 / Day25）。
 *
 * <p><b>它只做两件事：跑对话、确认写操作。</b>把这两件事放在一起是有意的 ——
 * 它们构成 Agent 的完整生命周期：<b>提议 → 确认 → 落库</b>。
 *
 * <p><b>确认接口的幂等性设计（面试可讲）：</b>
 * {@link ConfirmationService#take} 是"取出即删除"的原子操作，所以
 * <ul>
 *   <li>第一次确认：成功创建任务</li>
 *   <li>第二次确认（用户双击/网络重试）：草稿已不存在 → <b>404</b>，不会创建第二个任务</li>
 * </ul>
 * 代价是：如果"取草稿成功但落库失败"（比如数据库瞬间不可用），
 * 这张草稿就没了，用户必须重新发起。这个取舍是刻意的 ——
 * <b>写操作宁可少做一次，也不能多做一次。</b>
 * 如果将来要求"确认也必须可用"，正确做法是给 task 表加
 * {@code source_confirmation_id} 唯一索引，让数据库来保证不重复，
 * 而不是改成"先删后写"的松散流程。
 */
@Service
public class AgentService {

	private final AgentLoop agentLoop;

	private final ConfirmationService confirmations;

	private final TaskService tasks;

	public AgentService(AgentLoop agentLoop, ConfirmationService confirmations, TaskService tasks) {
		this.agentLoop = agentLoop;
		this.confirmations = confirmations;
		this.tasks = tasks;
	}

	/** 无状态 Agent 对话：不带历史，适合一次性提问与接口演示。 */
	public AgentResult chat(Long userId, String message) {
		return agentLoop.run(userId, null, message, List.of());
	}

	/**
	 * 确认并落库。
	 *
	 * <p>注意这里 <b>ownerId 只来自参数（即登录态）</b>，草稿里根本没存 ownerId。
	 * 别人的 confirmationId 拼出来的 Redis key 不一样，取不到 → 404。
	 */
	public Task confirm(Long userId, String confirmationId) {
		PendingConfirmation pending = confirmations.take(userId, confirmationId);

		Task task = new Task();
		task.setOwnerId(userId);
		task.setTitle(pending.title());
		task.setDescription(pending.description());
		task.setPriority(pending.priority() == null ? 3 : pending.priority());
		task.setStatus(TaskStatus.TODO);
		if (pending.dueDate() != null && !pending.dueDate().isBlank()) {
			try {
				task.setDueDate(LocalDateTime.parse(pending.dueDate()));
			}
			catch (DateTimeParseException ex) {
				throw new BusinessException("CONFIRMATION_CORRUPTED", "草稿的截止时间格式不合法，请重新发起");
			}
		}
		return tasks.create(task);
	}

	/** 取消草稿。不存在或已过期返回 false —— 对调用方来说结果一样：没有东西被创建。 */
	public boolean cancel(Long userId, String confirmationId) {
		return confirmations.cancel(userId, confirmationId);
	}

}
