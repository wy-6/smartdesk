package com.smartdesk.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.smartdesk.ai.entity.AiCallLog;
import com.smartdesk.ai.mapper.AiCallLogMapper;
import com.smartdesk.ai.provider.MockChatModel;
import com.smartdesk.task.mapper.TaskMapper;
import com.smartdesk.user.mapper.AppUserMapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 模块集成测试（Week3 / Day19-Day20）。
 *
 * <p>和 Week2 的集成测试一样，<b>用真实 MySQL，不用 H2</b>：
 * 本次要验证的是"调用记录真的落库了""别人的记录真的查不到"，
 * 这些都必须让 SQL 真的跑一遍。
 *
 * <p><b>越权用例先写</b>（第 2 周定下的规矩）：新表一建出来，
 * 第一件事就是证明"用户 A 看不到用户 B 的数据"，而不是等业务写完再补。
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
class AiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AiCallLogMapper aiCallLogMapper;

	@Autowired
	private TaskMapper taskMapper;

	@Autowired
	private AppUserMapper userMapper;

	@BeforeEach
	void cleanDatabase() {
		// 先子表后父表，否则外键会挡住删除
		aiCallLogMapper.delete(null);
		taskMapper.delete(null);
		userMapper.delete(null);
	}

	// ------------------------------------------------------------------
	// 越权隔离（先写）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("越权：用户只能查到自己那条调用记录")
	void getCall_ofAnotherUser_shouldReturn404() throws Exception {
		register("alice");
		register("bob");
		String aliceToken = login("alice");
		String bobToken = login("bob");

		Long aliceLogId = chatAndGetLogId(aliceToken);

		// bob 查 alice 的记录 → 404（不是 403，不泄露资源存在性）
		mockMvc.perform(get("/api/ai/calls/" + aliceLogId).header("Authorization", "Bearer " + bobToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

		// bob 的列表里也看不到 alice 的记录
		MvcResult bobList = mockMvc.perform(get("/api/ai/calls").header("Authorization", "Bearer " + bobToken))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode bobData = objectMapper.readTree(bobList.getResponse().getContentAsString()).path("data");
		assertThat(bobData).isEmpty();
	}

	@Test
	@DisplayName("越权：调用记录落库时 ownerId 必须来自登录态")
	void chatLog_shouldBeOwnedByCaller() throws Exception {
		Long aliceId = register("alice");
		register("bob");
		String aliceToken = login("alice");

		Long logId = chatAndGetLogId(aliceToken);

		AiCallLog log = aiCallLogMapper.selectById(logId);
		assertThat(log).isNotNull();
		assertThat(log.getOwnerId()).isEqualTo(aliceId);
	}

	// ------------------------------------------------------------------
	// 主链路
	// ------------------------------------------------------------------

	@Test
	@DisplayName("问答：成功调用应落库，且能通过 traceId 追溯")
	void chat_shouldPersistSuccessLog() throws Exception {
		register("alice");
		String token = login("alice");

		MvcResult result = mockMvc.perform(post("/api/ai/chat")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"介绍一下 SmartDesk"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.answer").isNotEmpty())
				.andExpect(jsonPath("$.data.meta.provider").value("mock"))
				.andReturn();

		String traceId = objectMapper.readTree(result.getResponse().getContentAsString())
				.path("data").path("meta").path("traceId").asText();
		assertThat(traceId).isNotBlank();

		List<AiCallLog> logs = aiCallLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
				.eq(AiCallLog::getTraceId, traceId));
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).getSuccess()).isTrue();
		assertThat(logs.get(0).getScene()).isEqualTo("chat");
		assertThat(logs.get(0).getTotalTokens()).isPositive();
	}

	@Test
	@DisplayName("任务草稿：应返回草稿但绝不写 task 表")
	void taskDraft_shouldNotPersistTask() throws Exception {
		register("alice");
		String token = login("alice");

		mockMvc.perform(post("/api/ai/task-draft")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"text":"明天下午把季度报告写完，这个比较重要"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").isNotEmpty())
				.andExpect(jsonPath("$.data.priority").value(4))
				.andExpect(jsonPath("$.data.dueDate").isNotEmpty());

		// 草稿不是任务：模型永远不能直接产生副作用
		assertThat(taskMapper.selectCount(null)).isZero();
	}

	@Test
	@DisplayName("任务草稿：模型输出不合法应返回 422，并记录失败原因")
	void taskDraft_whenInvalidJson_shouldReturn422() throws Exception {
		register("alice");
		String token = login("alice");

		mockMvc.perform(post("/api/ai/task-draft")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"text":"%s 明天交报告"}
								""".formatted(MockChatModel.FAIL_INVALID_JSON)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("AI_INVALID_OUTPUT"));

		List<AiCallLog> failures = aiCallLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
				.eq(AiCallLog::getSuccess, false));
		// 首次 + 一次修复重试
		assertThat(failures).hasSize(2);
		assertThat(failures.get(0).getErrorCode()).isEqualTo("AI_INVALID_OUTPUT");
	}

	@Test
	@DisplayName("问答：超时应重试到上限，返回 504 且落库 attempts=3")
	void chat_whenTimeout_shouldReturn504AndRecordAttempts() throws Exception {
		register("alice");
		String token = login("alice");

		mockMvc.perform(post("/api/ai/chat")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"%s 你好"}
								""".formatted(MockChatModel.FAIL_TIMEOUT)))
				.andExpect(status().isGatewayTimeout())
				.andExpect(jsonPath("$.errorCode").value("AI_TIMEOUT"));

		List<AiCallLog> logs = aiCallLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>());
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).getSuccess()).isFalse();
		assertThat(logs.get(0).getAttempts()).isEqualTo(3);
	}

	@Test
	@DisplayName("流式问答：应返回 text/event-stream 并推送 delta 与 done 事件")
	void stream_shouldEmitEvents() throws Exception {
		register("alice");
		String token = login("alice");

		MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
						.header("Authorization", "Bearer " + token)
						.accept(MediaType.TEXT_EVENT_STREAM)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"讲讲流式输出"}
								"""))
				.andExpect(request().asyncStarted())
				.andReturn();

		mockMvc.perform(asyncDispatch(result))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
	}

	@Test
	@DisplayName("未登录访问 AI 接口应返回 401")
	void chat_withoutToken_shouldReturn401() throws Exception {
		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------
	// 工具方法
	// ------------------------------------------------------------------

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

	private Long chatAndGetLogId(String token) throws Exception {
		mockMvc.perform(post("/api/ai/chat")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isOk());

		MvcResult list = mockMvc.perform(get("/api/ai/calls").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(list.getResponse().getContentAsString()).path("data").get(0).path("id").asLong();
	}

}
