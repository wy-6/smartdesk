package com.smartdesk.task.service;

import java.util.List;

import com.smartdesk.task.dto.TaskQuery;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;

/**
 * 任务业务接口（Day1 建立，Week2 扩展）。
 *
 * <p>Day1 要点：先定义接口再写实现（面向接口编程）。
 * Service 层职责：承载业务规则、编排多个数据操作、声明事务边界。
 * 它不应该知道 HTTP 的存在（不出现 {@code @RequestBody}/{@code @PathVariable}），
 * 也不应该直接拼 SQL。
 *
 * <p>Week2 扩展（Day8/Day10/Day11）：
 * <ul>
 *   <li>Day8：新增带多条件的 {@link TaskQuery} 查询</li>
 *   <li>Day10：{@code getById} 走缓存</li>
 *   <li>Day11：所有方法增加 {@code ownerId} 参数，<b>数据隔离下沉到 Service 层</b></li>
 * </ul>
 *
 * <p><b>Day11 最重要的设计决策：ownerId 由 Controller 传入还是 Service 内部取？</b>
 *
 * <p>本项目选择<b>Controller 取当前登录用户 → 显式传给 Service</b>，
 * 原因有三：
 * <ol>
 *   <li><b>可测试</b>：单元测试里直接传 ownerId=1L，不需要伪造 SecurityContext</li>
 *   <li><b>可读</b>：看方法签名就知道"这个查询是按用户过滤的"，
 *       不会漏看某个实现里偷偷加了过滤条件</li>
 *   <li><b>不隐藏副作用</b>：Service 方法的行为完全由参数决定</li>
 * </ol>
 * 代价是每个方法都要带 ownerId，略显啰嗦。
 * 另一种常见做法是 Service 内部调 {@code CurrentUser.getUserId()}，
 * 更简洁但会让 Service 与 Spring Security 耦合，单元测试要额外 mock。
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
	 * 按 id 查询任务（走缓存）。
	 *
	 * @param id 任务 id
	 * @return 任务；不存在时返回 {@code null}
	 */
	Task getById(Long id);

	/**
	 * 按 id 查询任务，并校验归属（不走缓存）。
	 *
	 * <p><b>Day11 要点：</b>这是防止越权访问的关键方法。
	 * 调用方传入 ownerId，Service 在 SQL 层就加上
	 * {@code WHERE id=? AND owner_id=?}，不是查出来再比。
	 *
	 * <p>为什么要在 SQL 层过滤而不是查出来再判断？
	 * <ul>
	 *   <li>SQL 层过滤：数据库不会把别人的数据发到应用层，防御更彻底</li>
	 *   <li>查出来再判断：如果代码写漏了，就泄露了</li>
	 * </ul>
	 *
	 * @param id 任务 id
	 * @param ownerId 当前用户 id
	 * @return 任务；不存在或不属于该用户时返回 {@code null}
	 */
	Task getByIdForOwner(Long id, Long ownerId);

	/**
	 * 查询任务列表（按状态过滤）。
	 *
	 * @param ownerId 当前用户 id
	 * @param status 状态过滤，可为 {@code null} 表示不过滤
	 * @param pageNum 页码，从 1 开始
	 * @param pageSize 每页条数
	 * @return 分页后的任务列表
	 */
	List<Task> list(Long ownerId, TaskStatus status, int pageNum, int pageSize);

	/**
	 * 多条件组合查询（Day8）。
	 *
	 * @param query 查询条件
	 * @return 匹配的任务列表
	 */
	List<Task> query(TaskQuery query);

	/**
	 * 更新任务状态（校验归属）。
	 *
	 * @param id 任务 id
	 * @param ownerId 当前用户 id
	 * @param status 新状态
	 * @return 更新后的任务；任务不存在或不属于该用户时返回 {@code null}
	 */
	Task updateStatus(Long id, Long ownerId, TaskStatus status);

	/**
	 * 删除任务（校验归属）。
	 *
	 * @param id 任务 id
	 * @param ownerId 当前用户 id
	 * @return 是否删除成功
	 */
	boolean delete(Long id, Long ownerId);

	/**
	 * 统计任务数量。
	 *
	 * @param ownerId 当前用户 id
	 * @param status 状态过滤，可为 {@code null}
	 * @return 数量
	 */
	long count(Long ownerId, TaskStatus status);

}
