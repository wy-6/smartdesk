package com.smartdesk.task.dto;

import java.time.LocalDateTime;

import com.smartdesk.task.entity.TaskStatus;

/**
 * 任务查询条件（Day8）。
 *
 * <p><b>为什么不用 @RequestParam 一堆散参？</b>
 *
 * <p>把筛选条件封装成一个对象，好处有三个：
 * <ol>
 *   <li><b>可扩展</b>：加一个筛选条件只改这个类 + Service 一行，不用改 Controller 签名</li>
 *   <li><b>可校验</b>：可以直接在字段上打 {@code @Min}/{@code @Max} 注解，
 *       Spring 自动校验（散参的话要在 Controller 里手工 if 判断）</li>
 *   <li><b>可测试</b>：单元测试直接 new 一个对象传进去，不用拼一堆参数</li>
 * </ol>
 *
 * <p><b>Day8 核心：每个可选条件都对应索引的一个前缀</b>
 *
 * <p>设计查询条件时，要先想清楚 SQL 长什么样，再决定需要哪些索引：
 * <pre>
 * SELECT * FROM task
 *  WHERE owner_id = ?          -- 必带（数据隔离）
 *    AND status = ?            -- 可选
 *    AND due_date &gt;= ?       -- 可选
 *    AND due_date &lt;= ?       -- 可选
 *    AND priority &gt;= ?       -- 可选
 *  ORDER BY priority DESC, due_date ASC
 *  LIMIT ? OFFSET ?
 * </pre>
 *
 * <p>对应索引 {@code idx_owner_status(owner_id, status)} 和
 * {@code idx_owner_due(owner_id, due_date)}。
 */
public class TaskQuery {

	/** 所属用户 id —— 必填，用于数据隔离。 */
	private Long ownerId;

	/** 状态过滤，null 表示不过滤。 */
	private TaskStatus status;

	/** 截止时间下界（含），null 表示不限。 */
	private LocalDateTime dueFrom;

	/** 截止时间上界（含），null 表示不限。 */
	private LocalDateTime dueTo;

	/** 优先级下界（含），null 表示不限。 */
	private Integer minPriority;

	/** 页码，从 1 开始。 */
	private int pageNum = 1;

	/** 每页条数。 */
	private int pageSize = 10;

	public Long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public TaskStatus getStatus() {
		return status;
	}

	public void setStatus(TaskStatus status) {
		this.status = status;
	}

	public LocalDateTime getDueFrom() {
		return dueFrom;
	}

	public void setDueFrom(LocalDateTime dueFrom) {
		this.dueFrom = dueFrom;
	}

	public LocalDateTime getDueTo() {
		return dueTo;
	}

	public void setDueTo(LocalDateTime dueTo) {
		this.dueTo = dueTo;
	}

	public Integer getMinPriority() {
		return minPriority;
	}

	public void setMinPriority(Integer minPriority) {
		this.minPriority = minPriority;
	}

	public int getPageNum() {
		return pageNum;
	}

	public void setPageNum(int pageNum) {
		this.pageNum = pageNum;
	}

	public int getPageSize() {
		return pageSize;
	}

	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
	}

}
