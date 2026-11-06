package com.smartdesk.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.smartdesk.task.entity.TaskStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 任务模块集成测试（Day13）。
 *
 * <p><b>用真实 MySQL，不用 H2。</b>
 *
 * <p>理由（面试可讲）：
 * <ul>
 *   <li>SQL 方言差异：MySQL 的 {@code LIMIT}、{@code ON DUPLICATE KEY UPDATE}、
 *       函数在 H2 里语法不同或不支持</li>
 *   <li>类型差异：H2 的 DECIMAL、日期精度和 MySQL 不一样</li>
 *   <li>索引和执行计划不同：优化器行为不一致</li>
 *   <li>最要命的：<b>"本地测试全绿、生产一跑就炸"</b></li>
 * </ul>
 *
 * <p><b>测试策略：每个测试前清空数据，保证互相不干扰。</b>
 * 这样测试是确定的（deterministic），不会因为执行顺序不同而失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:mysql://127.0.0.1:3307/smartdesk_test?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
		"spring.datasource.username=root",
		"spring.datasource.password=smartdesk_root",
		"spring.data.redis.host=127.0.0.1",
		"spring.data.redis.port=6380",
		"spring.data.redis.password=smartdesk_root"
})
class TaskIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private com.smartdesk.task.mapper.TaskMapper taskMapper;

	@Autowired
	private com.smartdesk.user.mapper.AppUserMapper userMapper;

	/** 测试用户的密码哈希（BCrypt，明文是 test123456）。 */
	private static final String TEST_PASSWORD_HASH =
			"$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC1jhlUD7y4oLk/BEtKUi";

	@BeforeEach
	void cleanDatabase() {
		taskMapper.delete(null);
		userMapper.delete(null);
	}

	/** 注册一个用户，返回 userId。 */
	private Long registerUser(String username) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"test123456"}
								""".formatted(username)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.path("data").path("id").asLong();
	}

	/** 登录拿 token。 */
	private String login(String username) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"test123456"}
								""".formatted(username)))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.path("data").path("token").asText();
	}

	/** 创建任务，返回 taskId。 */
	private Long createTask(String token, String title) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"%s","priority":3,"dueDate":"2026-12-31T18:00:00"}
								""".formatted(title)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.path("data").path("id").asLong();
	}

	@Test
	@DisplayName("集成：注册 → 登录 → 创建 → 查询 全链路应成功")
	void fullFlow_shouldSucceed() throws Exception {
		Long userId = registerUser("alice");
		assertThat(userId).isPositive();

		String token = login("alice");
		assertThat(token).isNotBlank();

		Long taskId = createTask(token, "写周报");
		assertThat(taskId).isPositive();

		// 数据确实落库了
		assertThat(taskMapper.selectById(taskId)).isNotNull();
		assertThat(taskMapper.selectById(taskId).getTitle()).isEqualTo("写周报");
	}

	@Test
	@DisplayName("集成：未登录访问任务列表应返回 401")
	void list_withoutToken_shouldReturn401() throws Exception {
		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("集成：伪造 token 应返回 401")
	void list_withForgedToken_shouldReturn401() throws Exception {
		mockMvc.perform(get("/api/tasks")
						.header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.forged.signature"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("集成：用户不应看到别人的任务（数据隔离）")
	void list_shouldNotLeakOtherUsersTasks() throws Exception {
		Long aliceId = registerUser("alice");
		Long bobId = registerUser("bob");
		assertThat(aliceId).isNotEqualTo(bobId);

		String aliceToken = login("alice");
		String bobToken = login("bob");

		createTask(aliceToken, "alice的任务");
		createTask(bobToken, "bob的任务");

		// alice 只看到自己的
		MvcResult aliceResult = mockMvc.perform(get("/api/tasks").header("Authorization", "Bearer " + aliceToken))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode aliceData = objectMapper.readTree(aliceResult.getResponse().getContentAsString()).path("data");
		assertThat(aliceData).hasSize(1);
		assertThat(aliceData.get(0).path("title").asText()).isEqualTo("alice的任务");

		// bob 只看到自己的
		MvcResult bobResult = mockMvc.perform(get("/api/tasks").header("Authorization", "Bearer " + bobToken))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode bobData = objectMapper.readTree(bobResult.getResponse().getContentAsString()).path("data");
		assertThat(bobData).hasSize(1);
		assertThat(bobData.get(0).path("title").asText()).isEqualTo("bob的任务");
	}

	@Test
	@DisplayName("集成：访问他人任务详情应返回 404")
	void getOtherUsersTask_shouldReturn404() throws Exception {
		registerUser("alice");
		registerUser("bob");
		String aliceToken = login("alice");
		String bobToken = login("bob");

		Long aliceTaskId = createTask(aliceToken, "alice的任务");

		mockMvc.perform(get("/api/tasks/" + aliceTaskId).header("Authorization", "Bearer " + bobToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("集成：修改他人任务状态应返回 404")
	void updateOtherUsersTask_shouldReturn404() throws Exception {
		registerUser("alice");
		registerUser("bob");
		String aliceToken = login("alice");
		String bobToken = login("bob");

		Long aliceTaskId = createTask(aliceToken, "alice的任务");

		mockMvc.perform(put("/api/tasks/" + aliceTaskId + "/status")
						.header("Authorization", "Bearer " + bobToken)
						.param("status", "DONE"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("集成：密码错误应返回 401 且不区分用户是否存在")
	void login_withWrongPassword_shouldReturn401() throws Exception {
		registerUser("alice");

		MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"alice","password":"wrongpassword"}
								"""))
				.andExpect(status().isUnauthorized())
				.andReturn();

		MvcResult noSuchUser = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"nobody","password":"wrongpassword"}
								"""))
				.andExpect(status().isUnauthorized())
				.andReturn();

		// 两个响应的 errorMessage 必须完全一致 —— 防用户枚举
		JsonNode a = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
		JsonNode b = objectMapper.readTree(noSuchUser.getResponse().getContentAsString());
		assertThat(a.path("errorMessage").asText()).isEqualTo(b.path("errorMessage").asText());
	}

	@Test
	@DisplayName("集成：注册响应中绝不能包含密码哈希")
	void register_shouldNotLeakPasswordHash() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"alice","password":"test123456"}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		String content = result.getResponse().getContentAsString();
		assertThat(content).doesNotContain("passwordHash");
		assertThat(content).doesNotContain("$2a$");
	}

	@Test
	@DisplayName("集成：按状态筛选应只返回匹配的任务")
	void list_withStatusFilter_shouldFilter() throws Exception {
		registerUser("alice");
		String token = login("alice");

		Long taskId = createTask(token, "写周报");
		createTask(token, "另一个任务");

		mockMvc.perform(put("/api/tasks/" + taskId + "/status")
						.header("Authorization", "Bearer " + token)
						.param("status", "DONE"))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(get("/api/tasks")
						.header("Authorization", "Bearer " + token)
						.param("status", "DONE"))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
		assertThat(data).hasSize(1);
		assertThat(data.get(0).path("status").asText()).isEqualTo(TaskStatus.DONE.name());
	}

}
