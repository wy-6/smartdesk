package com.smartdesk.config;

import java.time.LocalDateTime;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

/**
 * MyBatis-Plus 字段自动填充（Week2 补上）。
 *
 * <p><b>这是一个第 1 周埋下、第 2 周才暴露的 bug。</b>
 *
 * <p>第 1 周给 {@code Task.createdAt} 加了 {@code @TableField(fill = FieldFill.INSERT)}，
 * 看起来"会自动填充"。但 {@code @TableField(fill=...)} 只是<b>声明</b>，
 * 真正干活需要一个 {@link MetaObjectHandler} Bean —— 项目里当时并没有这个 Bean。
 *
 * <p><b>为什么 Task 表没报错？</b>
 * 因为 {@code task.created_at} 在建表时有 {@code DEFAULT CURRENT_TIMESTAMP}，
 * 数据库帮它兜住了底。
 *
 * <p><b>为什么 AppUser 表报错了？</b>
 * 新建的 {@code app_user} 表的 {@code created_at} 同样是
 * {@code DEFAULT CURRENT_TIMESTAMP}，但 MyBatis-Plus 生成的 INSERT 语句
 * <b>显式包含了 created_at 列并传了 null</b> —— 显式传 null 会覆盖数据库默认值，
 * 于是报 "Column 'created_at' cannot be null"。
 *
 * <p><b>面试可讲：</b>
 * <ul>
 *   <li>MyBatis-Plus 的自动填充是"声明 + 实现"两步，
 *       只做声明不会有任何效果（不报错，但也不生效）—— 这类"静默失效"最难查</li>
 *   <li>数据库默认值和 ORM 自动填充是<b>两套机制</b>，
 *       ORM 显式传 null 时会覆盖 DB 默认值</li>
 * </ul>
 *
 * <p><b>另一个坑：</b>数据库时区与应用时区不一致会导致时间偏移。
 * 这里统一用 {@link LocalDateTime} + 应用侧 {@code Asia/Shanghai}，
 * 配合 JDBC 参数里的 {@code serverTimezone=Asia/Shanghai} 保持一致。
 */
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

	/**
	 * 插入时填充 {@code createdAt} 和 {@code updatedAt}。
	 *
	 * @param metaObject 元对象
	 */
	@Override
	public void insertFill(MetaObject metaObject) {
		LocalDateTime now = LocalDateTime.now();
		// strictInsertFill：只有"字段存在 + 注解声明了 fill + 当前值为 null"才填充
		// 这样调用方如果自己设了值，不会被覆盖
		this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
		this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
	}

	/**
	 * 更新时填充 {@code updatedAt}。
	 *
	 * @param metaObject 元对象
	 */
	@Override
	public void updateFill(MetaObject metaObject) {
		// strictUpdateFill：无论是否有值都覆盖（updatedAt 语义上就是"现在"）
		this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
	}

}
