package com.smartdesk.task.entity;

/**
 * 任务状态枚举。
 *
 * <p>Day1 要点：用枚举而不是字符串常量表达有限状态。
 * 每个枚举值自带 {@code code}（持久化到数据库）与 {@code description}（展示用），
 * 业务代码里不再出现魔法字符串。
 *
 * <p>注意：枚举不能直接作为 MyBatis-Plus 的默认 TypeHandler 处理，
 * 需要在 {@code application.yml} 里配置 {@code type-enum-handler}，
 * 或者像本项目一样在实体上用 {@code @EnumValue} 标注 code 字段（见 Task 实体）。
 */
public enum TaskStatus {

	/** 待处理 */
	TODO("TODO", "待处理"),

	/** 进行中 */
	IN_PROGRESS("IN_PROGRESS", "进行中"),

	/** 已完成 */
	DONE("DONE", "已完成"),

	/** 已取消 */
	CANCELLED("CANCELLED", "已取消");

	private final String code;
	private final String description;

	TaskStatus(String code, String description) {
		this.code = code;
		this.description = description;
	}

	public String getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	/**
	 * 按 code 反查枚举。
	 *
	 * <p>Day4 要点：解析外部输入时不能直接 {@code valueOf}，
	 * 因为 valueOf 对非法值抛 {@code IllegalArgumentException}，
	 * 会变成 500 错误。这里返回 {@code null} 让上层返回 400。
	 */
	public static TaskStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (TaskStatus status : values()) {
			if (status.code.equalsIgnoreCase(code)) {
				return status;
			}
		}
		return null;
	}

}
