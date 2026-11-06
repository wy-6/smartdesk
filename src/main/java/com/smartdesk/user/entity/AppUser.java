package com.smartdesk.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 用户实体，对应表 {@code app_user}（Day11）。
 *
 * <p><b>安全红线：passwordHash 绝不能出现在任何返回给前端的 DTO 里。</b>
 *
 * <p>第 1 周我们把实体直接当响应体返回（{@code TaskResponse} 已经是 DTO，Task 实体没直接返回过）。
 * 但 User 实体里存了密码哈希，<b>一旦直接序列化返回，hash 就泄露了</b>。
 * 攻击者拿到 BCrypt 哈希后可以离线爆破。
 *
 * <p>所以这里加一道防线：{@code @JsonIgnore} 让 Jackson 直接忽略这个字段，
 * 就算哪天有人不小心把实体返回了，也不会泄露密码。
 *
 * <p><b>双保险思维：</b>
 * <ol>
 *   <li>第一道：返回用专门的 VO（{@code UserResponse}），根本不含哈希字段</li>
 *   <li>第二道：实体上加 {@code @JsonIgnore}，防止误用</li>
 * </ol>
 */
@TableName("app_user")
public class AppUser {

	/** 主键，数据库自增。 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 登录名，全局唯一。 */
	private String username;

	/**
	 * BCrypt 密码哈希。
	 *
	 * <p>用 {@code @JsonIgnore} 彻底切断它与 JSON 序列化的关系。
	 */
	@JsonIgnore
	private String passwordHash;

	/** 昵称。 */
	private String nickname;

	/** 是否启用。 */
	private Boolean enabled;

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

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
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
