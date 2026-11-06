package com.smartdesk.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.user.entity.AppUser;

/**
 * 用户 Mapper（Day11）。
 *
 * <p>继承 {@code BaseMapper<AppUser>} 就能白拿 CRUD：
 * insert / selectById / selectOne / updateById / deleteById / selectList 等。
 *
 * <p>这里额外手写一个 {@code selectByUsername}。登录是高频路径，
 * 用一条明确的 SQL 更直观，也比拼 LambdaQueryWrapper 更省事。
 *
 * <p><b>踩坑记录（Week2）</b>：只在接口里声明方法而不写实现，
 * MyBatis 启动时不会报错，但调用时会抛：
 * <pre>
 * BindingException: Invalid bound statement (not found):
 *   com.smartdesk.user.mapper.AppUserMapper.selectByUsername
 * </pre>
 *
 * <p><b>两种写法的选择：</b>
 * <ul>
 *   <li>{@code @Select} 注解 —— 简单查询用，快，不用建 XML 文件</li>
 *   <li>XML 映射文件 —— 复杂 SQL（动态条件、多表关联）用，可维护性更好</li>
 * </ul>
 * 无论哪种，<b>方法声明和实现必须成对出现</b>。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

	/**
	 * 按用户名查询用户。
	 *
	 * @param username 登录名
	 * @return 用户；不存在返回 {@code null}
	 */
	@Select("SELECT id, username, password_hash, nickname, enabled, created_at, updated_at "
			+ "FROM app_user WHERE username = #{username}")
	AppUser selectByUsername(String username);

}
