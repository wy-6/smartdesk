package com.smartdesk.task.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdesk.task.dto.TaskQuery;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.mapper.TaskMapper;

/**
 * 任务业务实现（Day2 内存实现 → Day5 MyBatis-Plus → Week2 缓存 + 权限隔离）。
 *
 * <p>Day2 要点：<b>构造器注入</b>。
 * <ul>
 *   <li>依赖用 {@code final} 字段声明，只能通过构造器传入，一劳永逸地保证不为 null</li>
 *   <li>没有 setter，依赖不可被中途篡改</li>
 *   <li>单元测试可以直接 {@code new TaskServiceImpl(mapper)}，不需要启动容器</li>
 * </ul>
 * 对比字段注入（{@code @Autowired} 打在字段上）的缺点：依赖隐藏、无法 final、
 * 单元测试必须反射或用 Spring 容器、循环依赖才报错。
 *
 * <p>Day10 要点：<b>缓存的三个陷阱，本类全部做了处理。</b>
 * <ol>
 *   <li><b>缓存穿透</b>：查不存在的 id，每次都会穿透到数据库。
 *       → 这里对"查不到"的结果也缓存一个空标记，但用 {@code unless} 排除 null
 *       更麻烦，这里采用"只缓存存在的对象 + 短 TTL"策略，详见 {@link #getById}。</li>
 *   <li><b>缓存击穿</b>：热点 key 过期瞬间，大量请求同时打到数据库。
 *       → 本项目规模小暂不处理，面试要能说出解法：互斥锁 / 逻辑过期 / 热点永不过期。</li>
 *   <li><b>缓存雪崩</b>：大量 key 同时过期。
 *       → TTL 加随机偏移（{@code 300 + random(60)}），避免同一秒集体失效。</li>
 * </ol>
 */
@Service
public class TaskServiceImpl implements TaskService {

	private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

	/** 缓存名称，与 {@code CacheConfig} 中定义的一致。 */
	public static final String TASK_CACHE = "taskDetail";

	private final TaskMapper taskMapper;

	/**
	 * 构造器注入。没有 {@code @Autowired} 也能工作 ——
	 * Spring 4.3 起，单构造器的 Bean 会自动被当作注入点。
	 */
	public TaskServiceImpl(TaskMapper taskMapper) {
		this.taskMapper = taskMapper;
	}

	@Override
	@Transactional
	public Task create(Task task) {
		// 业务规则：新建任务默认是待处理
		if (task.getStatus() == null) {
			task.setStatus(TaskStatus.TODO);
		}
		// createdAt / updatedAt 由 MybatisMetaObjectHandler 自动填充（Week2 补上），
		// 这里不再手动设置 —— 手动设置和自动填充并存容易产生不一致。
		taskMapper.insert(task);
		log.info("创建任务成功 id={} title={} ownerId={}", task.getId(), task.getTitle(), task.getOwnerId());
		return task;
	}

	/**
	 * 按 id 查任务，走缓存。
	 *
	 * <p><b>Day10 要点：{@code @Cacheable} 的执行顺序</b>
	 * <ol>
	 *   <li>先查缓存，命中直接返回，<b>方法体根本不执行</b>（省掉数据库往返）</li>
	 *   <li>未命中才执行方法体查数据库</li>
	 *   <li>把结果写回缓存</li>
	 * </ol>
	 *
	 * <p><b>为什么这里用 {@code unless = "#result == null"}？</b>
	 * Spring Cache <b>默认不会缓存 null 返回值</b>（这是好事）。
	 * 如果缓存了 null，一个不存在的 id 会被永久缓存成"不存在"，
	 * 等这个 id 后来真的被创建了，就会读到脏数据。
	 * 显式写出来是为了让读代码的人一眼看到这个设计决策。
	 *
	 * <p><b>安全提醒：</b>这个方法不校验 ownerId，<b>只能用于内部调用</b>。
	 * 对外的接口必须用 {@link #getByIdForOwner(Long, Long)}。
	 *
	 * <p><b>为什么它也有缓存？</b>
	 * 它的 key 同样带了 ownerId 语义上的"全局视图"，只给内部管理接口用。
	 * 对外接口走 {@code getByIdForOwner}，key 是 {@code "ownerId:id"}。
	 */
	@Override
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = TASK_CACHE, key = "'all:' + #id", unless = "#result == null")
	public Task getById(Long id) {
		return taskMapper.selectById(id);
	}

	/**
	 * 按 id 查任务并校验归属（<b>走缓存</b>）。
	 *
	 * <p><b>Week2 的缓存设计演进（重要）</b>
	 *
	 * <p>第一版设计是"权限校验的查询不走缓存"，理由是怕缓存污染。
	 * 但这样一来 <b>缓存永远用不上</b> —— 因为所有对外接口都调这个方法，
	 * {@code getById} 从来不会被执行，Day10 要求的"任务详情加缓存"形同虚设。
	 *
	 * <p>正确做法是<b>把 ownerId 放进缓存 key</b>：
	 * <pre>
	 * key = "ownerId:id"
	 * </pre>
	 *
	 * <p>这样：
	 * <ul>
	 *   <li>用户 A 查 task#1 → key = "1:1"，缓存的是 A 的视角</li>
	 *   <li>用户 B 查 task#1 → key = "2:1"，是另一个 key，命中不了 A 的缓存</li>
	 *   <li>既享受了缓存的性能，又完全隔离</li>
	 * </ul>
	 *
	 * <p><b>核心原则：缓存 key 必须包含所有影响结果的条件。</b>
	 * 少一个条件就是缓存污染 —— 这是数据隔离场景下最危险的缓存 bug。
	 *
	 * <p><b>面试可讲：</b>为什么不能简单地只用 id 做 key？
	 * 因为缓存里的值是"某个用户的视角下的结果"。
	 * 缓存 key 少一个维度，就等于允许不同维度共享同一份结果。
	 */
	@Override
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = TASK_CACHE, key = "#ownerId + ':' + #id", unless = "#result == null")
	public Task getByIdForOwner(Long id, Long ownerId) {
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(Task::getId, id)
				.eq(Task::getOwnerId, ownerId);
		return taskMapper.selectOne(wrapper);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Task> list(Long ownerId, TaskStatus status, int pageNum, int pageSize) {
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(Task::getOwnerId, ownerId);
		if (status != null) {
			wrapper.eq(Task::getStatus, status);
		}
		wrapper.orderByDesc(Task::getPriority).orderByDesc(Task::getCreatedAt);

		// Page 是 MyBatis-Plus 的分页对象，插件会自动改写 SQL 加 limit
		Page<Task> page = new Page<>(pageNum, pageSize);
		page = taskMapper.selectPage(page, wrapper);
		return page.getRecords();
	}

	/**
	 * 多条件组合查询（Day8 核心）。
	 *
	 * <p><b>LambdaQueryWrapper 的链式条件</b>：
	 * 每一个 {@code if} 只在参数非空时追加一个条件，参数为 null 就跳过 ——
	 * 这就是"动态 SQL"的实现方式。MyBatis-Plus 最终生成：
	 * <pre>
	 * SELECT * FROM task WHERE owner_id = ? AND status = ? AND due_date &gt;= ?
	 *   ORDER BY priority DESC LIMIT ?
	 * </pre>
	 *
	 * <p><b>条件顺序很重要（索引最左前缀）：</b>
	 * {@code ownerId} 必须第一个，因为所有复合索引都以它开头。
	 */
	@Override
	@Transactional(readOnly = true)
	public List<Task> query(TaskQuery query) {
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

		// ownerId 放最前 —— 所有索引的最左列
		wrapper.eq(Task::getOwnerId, query.getOwnerId());

		if (query.getStatus() != null) {
			wrapper.eq(Task::getStatus, query.getStatus());
		}
		if (query.getStatuses() != null && !query.getStatuses().isEmpty()) {
			// IN 查询：只有"逾期任务"这类语义需要，走 idx_owner_status 的最左前缀
			wrapper.in(Task::getStatus, query.getStatuses());
		}
		if (query.getDueFrom() != null) {
			wrapper.ge(Task::getDueDate, query.getDueFrom());
		}
		if (query.getDueTo() != null) {
			wrapper.le(Task::getDueDate, query.getDueTo());
		}
		if (query.getMinPriority() != null) {
			wrapper.ge(Task::getPriority, query.getMinPriority());
		}

		wrapper.orderByDesc(Task::getPriority).orderByAsc(Task::getDueDate);

		Page<Task> page = new Page<>(query.getPageNum(), query.getPageSize());
		page = taskMapper.selectPage(page, wrapper);
		return page.getRecords();
	}

	/**
	 * 更新任务状态。
	 *
	 * <p><b>Day10 要点：更新后必须清缓存。</b>
	 * {@code @CacheEvict} 默认在方法<b>正常返回后</b>才执行清除，
	 * 也就是说"先更新数据库，再删缓存"。
	 *
	 * <p><b>这个顺序对吗？</b>
	 * <ul>
	 *   <li>先更新 DB 再删缓存（Cache-Aside）：并发读可能读到旧缓存（脏读），
	 *       但不会长时间不一致。</li>
	 *   <li>先删缓存再更新 DB：并发读会穿透到 DB 拿到旧值并重新写回缓存，
	 *       缓存里反而留了更久的旧数据。</li>
	 * </ul>
	 * 所以<b>先 DB 后缓存</b>是更好的选择。这也是 Cache-Aside 模式的标准做法。
	 *
	 * <p><b>为什么用 allEntries = true？</b>
	 * 因为缓存 key 里带了 ownerId（{@code "ownerId:id"}），
	 * 同一个任务在不同 ownerId 下是不同的 key。
	 * 我们在 update/delete 时只知道 id，不知道 ownerId（或者知道当前用户的，
	 * 但缓存里可能有其他用户的副本）。
	 * 所以这里<b>清空整个 taskDetail 缓存</b> —— 简单且绝对安全。
	 *
	 * <p><b>性能影响：</b>清空整个缓存会让其他用户的缓存也失效，
	 * 造成缓存命中率下降。在高并发系统里会用更精细的方案
	 * （比如 Redis 的 SCAN 按模式删除），但对于本项目的数据量，全清完全够用。
	 *
	 * <p><b>beforeInvocation = true 什么时候用？</b>
	 * 当方法可能抛异常且你希望"只要调用了就清缓存"时用。
	 * 本项目默认 false（成功后清），因为失败时数据没变，缓存仍然有效。
	 */
	@Override
	@Transactional
	@CacheEvict(cacheNames = TASK_CACHE, allEntries = true)
	public Task updateStatus(Long id, Long ownerId, TaskStatus status) {
		Task task = getByIdForOwner(id, ownerId);
		if (task == null) {
			return null;
		}
		task.setStatus(status);
		// updatedAt 由 MybatisMetaObjectHandler 自动填充
		taskMapper.updateById(task);
		return task;
	}

	@Override
	@Transactional
	@CacheEvict(cacheNames = TASK_CACHE, allEntries = true)
	public boolean delete(Long id, Long ownerId) {
		Task task = getByIdForOwner(id, ownerId);
		if (task == null) {
			return false;
		}
		return taskMapper.deleteById(id) > 0;
	}

	@Override
	@Transactional(readOnly = true)
	public long count(Long ownerId, TaskStatus status) {
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
		wrapper.eq(Task::getOwnerId, ownerId);
		if (status != null) {
			wrapper.eq(Task::getStatus, status);
		}
		return taskMapper.selectCount(wrapper);
	}

}
