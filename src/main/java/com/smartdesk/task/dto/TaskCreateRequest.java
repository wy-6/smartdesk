package com.smartdesk.task.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建任务的请求体（Day3/4）。
 *
 * <p>Day4 要点：<b>为什么要用 DTO 而不直接用 Entity？</b>
 * <ol>
 *   <li>接口契约与存储结构解耦：以后 {@code task} 表加一个 {@code owner_password_hash}
 *       字段，不会因为实体多一个属性就把敏感字段暴露给前端</li>
 *   <li>入参可以只包含该接口需要的字段，避免"前端传什么就存什么"导致脏数据</li>
 *   <li>校验注解只能放在 DTO 上：Entity 上的约束是数据库语义，
 *       DTO 上的约束是接口语义，两者常常不同</li>
 * </ol>
 */
public class TaskCreateRequest {

	@NotBlank(message = "任务标题不能为空")
	@Size(max = 200, message = "任务标题长度不能超过 200")
	private String title;

	@Size(max = 2000, message = "任务描述长度不能超过 2000")
	private String description;

	@NotNull(message = "任务优先级不能为空")
	@Min(value = 1, message = "优先级最小为 1")
	@Max(value = 5, message = "优先级最大为 5")
	private Integer priority;

	@NotNull(message = "截止时间不能为空")
	private LocalDateTime dueDate;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public LocalDateTime getDueDate() {
		return dueDate;
	}

	public void setDueDate(LocalDateTime dueDate) {
		this.dueDate = dueDate;
	}

}
