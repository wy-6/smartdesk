package com.smartdesk.task.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 任务实体，对应数据库表 {@code task}。
 *
 * <p>Day1 要点：实体只描述"数据长什么样"，不写任何业务逻辑。
 * 业务逻辑属于 Service，HTTP 协议属于 Controller/ DTO。
 *
 * <p>Day5 要点：MyBatis-Plus 通过注解把实体映射到表：
 * <ul>
 *   <li>{@code @TableName} 指定表名</li>
 *   <li>{@code @TableId} 指定主键，{@code IdType.AUTO} 表示数据库自增</li>
 *   <li>{@code @TableField} 可覆盖默认列名映射</li>
 *   <li>{@code @EnumValue} 指定枚举用哪个字段入库</li>
 * </ul>
 */
@TableName("task")
public class Task {

	/** 主键，数据库自增。 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 任务标题。 */
	private String title;

	/** 任务描述。 */
	private String description;

	/** 任务状态，入库存 code。 */
	@EnumValue
	private TaskStatus status;

	/** 优先级，数值越大越优先。 */
	private Integer priority;

	/** 所属用户 id —— Week2 做数据隔离时靠它过滤。 */
	private Long ownerId;

	/** 截止日期。 */
	private LocalDateTime dueDate;

	/** 创建时间，插入时自动填充。 */
	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

	/** 更新时间，插入与更新时自动填充。 */
	@TableField(fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updatedAt;

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

	public TaskStatus getStatus() {
		return status;
	}

	public void setStatus(TaskStatus status) {
		this.status = status;
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
