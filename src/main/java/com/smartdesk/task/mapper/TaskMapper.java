package com.smartdesk.task.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdesk.task.entity.Task;

/**
 * 任务 Mapper（Day5）。
 *
 * <p>MyBatis-Plus 的核心价值：继承 {@link BaseMapper} 就白拿
 * insert / selectById / selectList / updateById / deleteById 等全套单表 CRUD，
 * 一行 SQL 都不用写。
 *
 * <p>为什么用 {@code @Mapper} 而不是 {@code @MapperScan}？
 * 两种都能让接口被注册成 Bean：
 * <ul>
 *   <li>{@code @Mapper}：逐个接口标注，项目小的时候直观</li>
 *   <li>{@code @MapperScan("com.smartdesk.**.mapper")}：包路径扫描，项目大的时候省事</li>
 * </ul>
 * 本项目用 {@code @Mapper}，每个接口都能一眼看出自己被扫描了。
 *
 * <p><b>常见坑</b>：Spring Boot 4 必须使用
 * {@code mybatis-plus-spring-boot4-starter}。
 * 如果误用 {@code mybatis-plus-spring-boot3-starter}，
 * 启动会报 {@code No qualifying bean of type 'TaskMapper' available}
 * —— 因为 boot3 starter 的自动配置类根本没被加载。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

}
