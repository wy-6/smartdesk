package com.smartdesk.task.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.mapper.TaskMapper;

/**
 * 任务业务实现（Day2 用内存实现，Day5 换成 MyBatis-Plus 实现）。
 *
 * <p>Day2 要点：<b>构造器注入</b>。
 * <ul>
 *   <li>依赖用 {@code final} 字段声明，只能通过构造器传入，一劳永逸地保证不为 null</li>
 *   <li>没有 setter，依赖不可被中途篡改</li>
 *   <li>单元测试可以直接 {@code new TaskServiceImpl(mapper)}，不需要启动容器</li>
 * </ul>
 * 对比字段注入（{@code @Autowired} 打在字段上）的缺点：依赖隐藏、无法 final、
 * 单元测试必须反射或用 Spring 容器、循环依赖才报错。
 */
@Service
public class TaskServiceImpl implements TaskService {

	private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

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
		if (task.getCreatedAt() == null) {
			task.setCreatedAt(LocalDateTime.now());
		}
		task.setUpdatedAt(LocalDateTime.now());
		taskMapper.insert(task);
		log.info("创建任务成功 id={} title={}", task.getId(), task.getTitle());
		return task;
	}

	@Override
	@Transactional(readOnly = true)
	public Task getById(Long id) {
		return taskMapper.selectById(id);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Task> list(TaskStatus status, int pageNum, int pageSize) {
		// LambdaQueryWrapper：类型安全的条件构造，字段名写错编译期就报错
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
		if (status != null) {
			wrapper.eq(Task::getStatus, status);
		}
		wrapper.orderByDesc(Task::getPriority).orderByDesc(Task::getCreatedAt);

		// Page 是 MyBatis-Plus 的分页对象，插件会自动改写 SQL 加 limit
		Page<Task> page = new Page<>(pageNum, pageSize);
		page = taskMapper.selectPage(page, wrapper);
		return page.getRecords();
	}

	@Override
	@Transactional
	public Task updateStatus(Long id, TaskStatus status) {
		Task task = taskMapper.selectById(id);
		if (task == null) {
			return null;
		}
		task.setStatus(status);
		task.setUpdatedAt(LocalDateTime.now());
		taskMapper.updateById(task);
		return task;
	}

	@Override
	@Transactional
	public boolean delete(Long id) {
		return taskMapper.deleteById(id) > 0;
	}

	@Override
	@Transactional(readOnly = true)
	public long count(TaskStatus status) {
		LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
		if (status != null) {
			wrapper.eq(Task::getStatus, status);
		}
		return taskMapper.selectCount(wrapper);
	}

}
