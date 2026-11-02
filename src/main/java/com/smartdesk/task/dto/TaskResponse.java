package com.smartdesk.task.dto;

import java.time.LocalDateTime;

import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;

/**
 * 任务响应体（Day3）。
 *
 * <p>注意这里返回的是 {@code status} 的 code 字符串而不是枚举对象本身。
 * 枚举序列化后长这样：{"code":"TODO","description":"待处理"}，
 * 多一层包装，前端还得再解一次。返回扁平字符串更符合 API 惯例。
 */
public class TaskResponse {

	private Long id;

	private String title;

	private String description;

	private String status;

	private String statusDescription;

	private Integer priority;

	private Long ownerId;

	private LocalDateTime dueDate;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	/**
	 * 由实体转换为响应对象。
	 *
	 * <p>Day4 要点：DTO 的静态工厂方法放在 DTO 自己身上，
	 * 而不是 Controller 里手写一堆 setter —— 转换逻辑集中，改一处就够。
	 */
	public static TaskResponse from(Task task) {
		if (task == null) {
			return null;
		}
		TaskResponse response = new TaskResponse();
		response.setId(task.getId());
		response.setTitle(task.getTitle());
		response.setDescription(task.getDescription());
		if (task.getStatus() != null) {
			response.setStatus(task.getStatus().getCode());
			response.setStatusDescription(task.getStatus().getDescription());
		}
		response.setPriority(task.getPriority());
		response.setOwnerId(task.getOwnerId());
		response.setDueDate(task.getDueDate());
		response.setCreatedAt(task.getCreatedAt());
		response.setUpdatedAt(task.getUpdatedAt());
		return response;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

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

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getStatusDescription() {
		return statusDescription;
	}

	public void setStatusDescription(String statusDescription) {
		this.statusDescription = statusDescription;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public LocalDateTime getDueDate() {
		return dueDate;
	}

	public void setDueDate(LocalDateTime dueDate) {
		this.dueDate = dueDate;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
