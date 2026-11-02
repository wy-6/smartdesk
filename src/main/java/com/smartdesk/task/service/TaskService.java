package com.smartdesk.task.service;

import java.util.List;

import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;

/**
 * 任务业务接口（Day1）。
 *
 * <p>Day1 要点：先定义接口再写实现（面向接口编程）。
 * Service 层职责：承载业务规则、编排多个数据操作、声明事务边界。
 * 它不应该知道 HTTP 的存在（不出现 {@code @RequestBody}/{@code @PathVariable}），
 * 也不应该直接拼 SQL。
 *
 * <p>Day2/3 会用 Spring 的构造器注入把实现类装配进来。
 * 接口存在的主要收益是可测试性：单元测试用 Mockito mock 实现类即可，
 * 不需要启动整个 Spring 容器。
 */
public interface TaskService {

	/**
	 * 创建任务。
	 *
	 * @param task 待创建的任务，id 由数据库生成
	 * @return 创建后的任务（含自增 id）
	 */
	Task create(Task task);

	/**
	 * 按 id 查询任务。
	 *
	 * @param id 任务 id
	 * @return 任务；不存在时返回 {@code null}
	 */
	Task getById(Long id);

	/**
	 * 查询任务列表。
	 *
	 * @param status 状态过滤，可为 {@code null} 表示不过滤
	 * @param pageNum 页码，从 1 开始
	 * @param pageSize 每页条数
	 * @return 分页后的任务列表
	 */
	List<Task> list(TaskStatus status, int pageNum, int pageSize);

	/**
	 * 更新任务状态。
	 *
	 * @param id 任务 id
	 * @param status 新状态
	 * @return 更新后的任务；任务不存在时返回 {@code null}
	 */
	Task updateStatus(Long id, TaskStatus status);

	/**
	 * 删除任务。
	 *
	 * @param id 任务 id
	 * @return 是否删除成功
	 */
	boolean delete(Long id);

	/**
	 * 统计任务数量。
	 *
	 * @param status 状态过滤，可为 {@code null}
	 * @return 数量
	 */
	long count(TaskStatus status);

}
