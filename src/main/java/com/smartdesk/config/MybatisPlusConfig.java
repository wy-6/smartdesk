package com.smartdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

/**
 * MyBatis-Plus 配置（Day5）。
 *
 * <p>Day5 要点：<b>分页插件必须手动注册</b>。
 * MyBatis-Plus 不会自动拦截分页查询 —— 它只提供了拦截器类，
 * 你得告诉它"用哪种数据库的方言"（LIMIT 语法各家不同）。
 *
 * <p>另外 MyBatis-Plus 3.5.9 起把 {@code PaginationInnerInterceptor}
 * 从核心包拆到了独立模块 {@code mybatis-plus-jsqlparser}，
 * 所以 pom.xml 里必须显式引入它，否则编译期就报找不到类。
 */
@Configuration
public class MybatisPlusConfig {

	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
		// 指定数据库类型，插件才能生成正确的 LIMIT 语句
		PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
		// 单页最大条数限制，防止 pageSize 传 100000 拖垮数据库
		pagination.setMaxLimit(100L);
		// 请求页码超过最大页时返回空列表，而不是回到首页
		pagination.setOverflow(false);
		interceptor.addInnerInterceptor(pagination);
		return interceptor;
	}

}
