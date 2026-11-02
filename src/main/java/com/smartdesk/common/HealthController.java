package com.smartdesk.common;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口（Day2 产出物）。
 *
 * <p>Day2 验收要求：实现 {@code GET /api/health} 且应用能启动。
 *
 * <p>为什么要单独做这个接口？
 * <ul>
 *   <li>它是"应用是否启动成功"的最小验证点，不需要连数据库、不需要参数</li>
 *   <li>生产环境里，K8s 的 livenessProbe / readinessProbe 会打这个接口</li>
 *   <li>Spring Boot 自带的 Actuator 也有 {@code /actuator/health}，
 *       但自己写一个更轻量，也方便面试时讲清楚两者的区别</li>
 * </ul>
 */
@RestController
public class HealthController {

	@GetMapping("/api/health")
	public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", "UP");
		data.put("application", "smartdesk");
		data.put("timestamp", LocalDateTime.now());
		return ResponseEntity.ok(ApiResponse.success(data));
	}

}
