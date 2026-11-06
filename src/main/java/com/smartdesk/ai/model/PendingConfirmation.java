package com.smartdesk.ai.model;

/**
 * 待确认的写操作草稿（Week4 / Day25）。
 *
 * <p><b>为什么要有这个对象？</b>
 * 因为"创建任务"这种有副作用的操作不能由模型直接完成。模型生成草稿 →
 * 存进 Redis → 返回 {@code confirmationId} → 用户在前端点"确认" →
 * 后端才真正落库。整个过程里，<b>模型只负责提议，人负责批准</b>。
 *
 * <p><b>为什么字段是平铺的而不是内嵌一个 Task 实体？</b>
 * 实体是数据库结构的镜像，会随表结构变化。草稿是"给人看的提案"，
 * 只需要展示字段。而且实体带 {@code ownerId}，如果直接序列化进 Redis，
 * 就多了一个"可以被篡改的身份字段" —— 草稿里干脆不存 ownerId，
 * 它由 Redis key 的一部分（{@code ai:confirm:{ownerId}:{id}}）承载。
 *
 * <p>{@code dueDate} 用 ISO 字符串而不是 {@code LocalDateTime}：
 * 存进 Redis 的是 JSON，字符串格式最稳定，不依赖 Jackson 的 JavaTime 模块配置。
 *
 * @param confirmationId 确认号，前端拿它调确认接口
 * @param ownerId 归属用户，来自登录态
 * @param sessionId 会话 id，可为 null
 * @param toolName 产生该草稿的工具名，用于审计
 * @param title 任务标题
 * @param description 任务描述
 * @param priority 优先级 1-5
 * @param dueDate ISO-8601 时间字符串，可为 null
 * @param createdAt 创建时间戳（毫秒），用于展示"还剩多久过期"
 */
public record PendingConfirmation(String confirmationId, Long ownerId, Long sessionId, String toolName, String title,
		String description, Integer priority, String dueDate, long createdAt) {

}
