package com.smartdesk.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdesk.ai.entity.AiToolCallLog;
import com.smartdesk.ai.entity.ChatMessage;
import com.smartdesk.ai.mapper.AiCallLogMapper;
import com.smartdesk.ai.mapper.AiToolCallLogMapper;
import com.smartdesk.ai.mapper.ChatMessageMapper;
import com.smartdesk.ai.mapper.ChatSessionMapper;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.mapper.TaskMapper;
import com.smartdesk.user.mapper.AppUserMapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent 集成测试（Week4 / Day24-27）。
 *
 * <p><b>这个类用真实 MySQL + 真实 Redis，因为它要验证的东西都是"真的"才行：</b>
 * <ul>
 *   <li>工具查数据时，SQL 里到底带没带 {@code owner_id}</li>
 *   <li>"确认"到底有没有真的插入一行 task</li>
 *   <li>重复确认是不是真的只创建一次</li>
 *   <li>越权访问是不是真的 404</li>
 * </ul>
 * 用 mock 掉这些，测试就变成了"验证我自己写的 if 判断"。
 *
 * <p><b>越权用例写在最前面</b>——这是第 2 周定下的规矩：新表一建出来，
 * 先证明"用户 A 看不到用户 B 的数据"，再写业务。
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
		"spring.data.redis.password=smartdesk_root" })
class AgentIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TaskMapper taskMapper;

	@Autowired
	private AppUserMapper userMapper;

	@Autowired
	private AiCallLogMapper aiCallLogMapper;

	@Autowired
	private AiToolCallLogMapper toolCallLogMapper;

	@Autowired
	private ChatSessionMapper sessionMapper;

	@Autowired
	private ChatMessageMapper messageMapper;

	@BeforeEach
	void cleanDatabase() {
		// 顺序：先子表后父表，否则外键挡住删除
		messageMapper.delete(null);
		sessionMapper.delete(null);
		toolCallLogMapper.delete(null);
		aiCallLogMapper.delete(null);
		taskMapper.delete(null);
		userMapper.delete(null);
	}

	// ------------------------------------------------------------------
	// 越权隔离（先写）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("越权：工具只能查到自己用户的任务，别人的任务不会出现在回答里")
	void agent_shouldNotLeakOtherUsersTasks() throws Exception {
		Long aliceId = register("alice");
		register("bob");
		String bobToken = login("bob");
		insertTask(aliceId, "Alice 的机密任务", TaskStatus.TODO, LocalDateTime.now().minusDays(1));

		String answer = agent(bobToken, "我有哪些任务");

		assertThat(answer).doesNotContain("Alice 的机密任务");
	}

	@Test
	@DisplayName("越权：读别人的会话 → 404，不是 403")
	void session_ofAnotherUser_shouldReturn404() throws Exception {
		register("alice");
		register("bob");
		String aliceToken = login("alice");
		String bobToken = login("bob");

		long sessionId = createSession(aliceToken);

		mockMvc.perform(get("/api/ai/sessions/" + sessionId + "/messages").header("Authorization",
				"Bearer " + bobToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("越权：用别人的确认号确认 → 404，且不创建任何任务")
	void confirm_withAnotherUsersId_shouldReturn404() throws Exception {
		register("alice");
		Long bobId = register("bob");
		String aliceToken = login("alice");
		String bobToken = login("bob");

		String confirmationId = createDraft(aliceToken, "帮我创建一个明天提交季度报告的任务");
		long tasksBefore = countTasks(bobId);

		mockMvc.perform(post("/api/ai/confirmations/" + confirmationId + "/confirm")
						.header("Authorization", "Bearer " + bobToken))
				.andExpect(status().isNotFound());

		assertThat(countTasks(bobId)).isEqualTo(tasksBefore);
	}

	@Test
	@DisplayName("工具 schema：任何工具的参数字段里都不能出现 userId / ownerId")
	void tools_shouldNotExposeIdentityParameters() throws Exception {
		register("alice");
		String token = login("alice");

		MvcResult result = mockMvc.perform(get("/api/ai/tools").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode tools = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
		assertThat(tools).isNotEmpty();
		for (JsonNode tool : tools) {
			JsonNode properties = tool.path("parameters").path("properties");
			assertThat(properties.has("userId")).as(tool.path("name").asText()).isFalse();
			assertThat(properties.has("ownerId")).as(tool.path("name").asText()).isFalse();
			// createTask 是唯一有副作用的工具，必须被标出来
			if ("createTask".equals(tool.path("name").asText())) {
				assertThat(tool.path("sideEffect").asBoolean()).isTrue();
			}
		}
	}

	// ------------------------------------------------------------------
	// 主链路
	// ------------------------------------------------------------------

	@Test
	@DisplayName("完整链路：问逾期任务 → 调 listTasks → 基于真实数据回答")
	void agent_findsOverdueTasks() throws Exception {
		Long aliceId = register("alice");
		String token = login("alice");
		insertTask(aliceId, "补交季度报告", TaskStatus.TODO, LocalDateTime.now().minusDays(2));
		insertTask(aliceId, "下季度的规划", TaskStatus.TODO, LocalDateTime.now().plusDays(10));

		MvcResult result = mockMvc.perform(post("/api/ai/agent")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"我有哪些逾期的任务"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.toolCalls[0].name").value("listTasks"))
				.andExpect(jsonPath("$.data.toolCalls[0].ok").value(true))
				.andReturn();

		String answer = objectMapper.readTree(result.getResponse().getContentAsString())
				.path("data").path("answer").asText();
		assertThat(answer).contains("补交季度报告");
		// 未逾期的那条不应该出现
		assertThat(answer).doesNotContain("下季度的规划");
	}

	@Test
	@DisplayName("写操作：createTask 只生成草稿，任务表零增长")
	void createTask_shouldNotPersistBeforeConfirmation() throws Exception {
		Long aliceId = register("alice");
		String token = login("alice");

		MvcResult result = mockMvc.perform(post("/api/ai/agent")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"帮我创建一个明天提交季度报告的任务"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.toolCalls[0].name").value("createTask"))
				.andReturn();

		String answer = objectMapper.readTree(result.getResponse().getContentAsString())
				.path("data").path("answer").asText();
		assertThat(answer).contains("确认");
		// 关键：模型说了要创建，但库里什么都没有
		assertThat(countTasks(aliceId)).isZero();
	}

	@Test
	@DisplayName("确认：第一次创建成功，第二次 404 且不会重复创建")
	void confirm_shouldBeIdempotent() throws Exception {
		Long aliceId = register("alice");
		String token = login("alice");

		String confirmationId = createDraft(token, "帮我创建一个明天提交季度报告的任务");

		mockMvc.perform(post("/api/ai/confirmations/" + confirmationId + "/confirm")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").isNotEmpty())
				.andExpect(jsonPath("$.data.status").value("TODO"));

		assertThat(countTasks(aliceId)).isEqualTo(1);

		// 用户双击 / 网络重试：草稿已被原子删除，返回 404
		mockMvc.perform(post("/api/ai/confirmations/" + confirmationId + "/confirm")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());

		// 依然只有一条 —— 这是"宁可失败不重复创建"的取舍
		assertThat(countTasks(aliceId)).isEqualTo(1);
	}

	@Test
	@DisplayName("多轮会话：两轮之后消息落库，历史被带进第二轮")
	void session_shouldKeepHistory() throws Exception {
		register("alice");
		String token = login("alice");
		long sessionId = createSession(token);

		sendMessage(token, sessionId, "我还有多少任务");
		sendMessage(token, sessionId, "那我有哪些逾期任务");

		List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
				.eq(ChatMessage::getSessionId, sessionId)
				.orderByAsc(ChatMessage::getId));

		// 2 轮 × (user + assistant) = 4 条
		assertThat(messages).hasSize(4);
		assertThat(messages).extracting(ChatMessage::getRole)
				.containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
		// 助手消息里要带工具调用摘要，前端才能展示"AI 做了什么"
		assertThat(messages.get(1).getToolCalls()).isNotNull().contains("countTasks");
	}

	@Test
	@DisplayName("审计：每次工具执行都落库，且 ownerId 来自登录态")
	void toolAudit_shouldBePersisted() throws Exception {
		Long aliceId = register("alice");
		String token = login("alice");
		insertTask(aliceId, "写周报", TaskStatus.TODO, LocalDateTime.now().minusDays(1));

		agent(token, "我有哪些逾期的任务");

		List<AiToolCallLog> logs = toolCallLogMapper.selectList(null);
		assertThat(logs).isNotEmpty();
		assertThat(logs.get(0).getOwnerId()).isEqualTo(aliceId);
		assertThat(logs.get(0).getToolName()).isEqualTo("listTasks");
		assertThat(logs.get(0).getSuccess()).isTrue();
		assertThat(logs.get(0).getTraceId()).isNotBlank();
	}

	@Test
	@DisplayName("越权参数：模型传 userId 会被剥离，审计里能看到 privilegeStripped")
	void privilegeParameter_shouldBeStrippedAndAudited() throws Exception {
		Long aliceId = register("alice");
		register("bob");
		String token = login("alice");
		// 剧本模式：直接让"模型"传一个别人的 userId
		Long bobId = userMapper.selectOne(new LambdaQueryWrapper<com.smartdesk.user.entity.AppUser>()
				.eq(com.smartdesk.user.entity.AppUser::getUsername, "bob")).getId();
		insertTask(bobId, "Bob 的任务", TaskStatus.TODO, LocalDateTime.now().minusDays(1));

		String answer = agent(token, "[[TOOL:listTasks:{\"userId\":" + bobId + ",\"limit\":5}]] 我有哪些任务");

		assertThat(answer).doesNotContain("Bob 的任务");
		List<AiToolCallLog> logs = toolCallLogMapper.selectList(null);
		assertThat(logs).anyMatch(log -> Boolean.TRUE.equals(log.getPrivilegeStripped()));
	}

	@Test
	@DisplayName("死循环：模型反复调同一工具 → 422，不会把 token 烧光")
	void loopInjection_shouldTerminate() throws Exception {
		register("alice");
		String token = login("alice");

		mockMvc.perform(post("/api/ai/agent")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"[[FAIL:LOOP]] 统计一下任务"}
								"""))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	@DisplayName("未登录：所有 Agent 接口都要 401")
	void unauthenticated_shouldReturn401() throws Exception {
		mockMvc.perform(get("/api/ai/tools")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/ai/agent")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------
	// 辅助
	// ------------------------------------------------------------------

	private String agent(String token, String message) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/ai/agent")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("message", message))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("answer").asText();
	}

	private long createSession(String token) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/ai/sessions")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
	}

	private void sendMessage(String token, long sessionId, String message) throws Exception {
		mockMvc.perform(post("/api/ai/sessions/" + sessionId + "/messages")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("message", message))))
				.andExpect(status().isOk());
	}

	/**
	 * 触发一次创建草稿，并从审计记录里取回 confirmationId。
	 *
	 * <p>为什么不从回答文本里正则提取？因为那依赖 Mock 的文案，
	 * 换成真实模型就会挂。审计表里的 payload 是协议层的真实结果，稳定得多。
	 */
	private String createDraft(String token, String message) throws Exception {
		agent(token, message);
		MvcResult result = mockMvc.perform(get("/api/ai/tool-calls?page=1&pageSize=5")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode logs = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
		for (JsonNode log : logs) {
			if (!"createTask".equals(log.path("toolName").asText())) {
				continue;
			}
			JsonNode payload = objectMapper.readTree(log.path("resultSummary").asText());
			String confirmationId = payload.path("confirmationId").asText();
			if (!confirmationId.isBlank()) {
				return confirmationId;
			}
		}
		throw new AssertionError("没有找到 createTask 的待确认草稿");
	}

	private void insertTask(Long ownerId, String title, TaskStatus status, LocalDateTime dueDate) {
		Task task = new Task();
		task.setOwnerId(ownerId);
		task.setTitle(title);
		task.setStatus(status);
		task.setPriority(3);
		task.setDueDate(dueDate);
		taskMapper.insert(task);
	}

	private long countTasks(Long ownerId) {
		return taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getOwnerId, ownerId));
	}

	private Long register(String username) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"test123456"}
								""".formatted(username)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
	}

	private String login(String username) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"test123456"}
								""".formatted(username)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
	}

}
