package com.smartdesk.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.smartdesk.ai.dto.AiMeta;
import com.smartdesk.ai.dto.ChatAnswer;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.service.AiCallLogService;
import com.smartdesk.ai.service.AiService;
import com.smartdesk.config.SecurityConfig;
import com.smartdesk.security.JwtPrincipal;
import com.smartdesk.security.JwtService;

/**
 * AI 接口 Web 层测试（Week3 / Day20）。
 *
 * <p>关注点只有三个：
 * <ol>
 *   <li><b>认证</b>：未登录必须 401，而不是让 Service 里抛 NPE</li>
 *   <li><b>错误码映射</b>：AI_TIMEOUT → 504、AI_INVALID_OUTPUT → 422，
 *       这是 AI 接口和普通 CRUD 最不一样的地方</li>
 *   <li><b>SSE 协议</b>：事件名和顺序要对，前端才能按约定解析</li>
 * </ol>
 * 业务逻辑的正确性由 Service 层测试保证，这里不重复。
 */
@WebMvcTest(controllers = { AiController.class, AiCallLogController.class })
@Import(SecurityConfig.class)
class AiControllerTest {

	private static final Long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AiService aiService;

	@MockitoBean
	private AiCallLogService aiCallLogService;

	@MockitoBean
	private JwtService jwtService;

	private RequestPostProcessor loginAs(Long userId) {
		JwtPrincipal principal = new JwtPrincipal(userId, "tester");
		UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		return authentication(auth);
	}

	private AiMeta meta() {
		return new AiMeta("chat", "mock", "mock-rule-v1", 10, 20, 5, 1, "trace-1");
	}

	@Test
	@DisplayName("未登录访问 AI 接口应返回 401")
	void chat_withoutToken_shouldReturn401() throws Exception {
		mockMvc.perform(post("/api/ai/chat").contentType("application/json").content("""
				{"message":"你好"}
				"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("空消息应返回 400")
	void chat_withBlankMessage_shouldReturn400() throws Exception {
		mockMvc.perform(post("/api/ai/chat").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"message":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
	}

	@Test
	@DisplayName("问答成功应返回回答与调用元信息")
	void chat_shouldReturnAnswerAndMeta() throws Exception {
		when(aiService.chat(eq(USER_ID), anyString())).thenReturn(new ChatAnswer("你好，我是 SmartDesk", meta()));

		mockMvc.perform(post("/api/ai/chat").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.answer").value("你好，我是 SmartDesk"))
				.andExpect(jsonPath("$.data.meta.traceId").value("trace-1"))
				.andExpect(jsonPath("$.data.meta.attempts").value(1));
	}

	@Test
	@DisplayName("模型超时应映射为 504，而不是 500")
	void chat_whenTimeout_shouldReturn504() throws Exception {
		when(aiService.chat(anyLong(), anyString()))
				.thenThrow(new AiException(AiErrorCode.TIMEOUT, "模型响应超时"));

		mockMvc.perform(post("/api/ai/chat").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isGatewayTimeout())
				.andExpect(jsonPath("$.errorCode").value("AI_TIMEOUT"));
	}

	@Test
	@DisplayName("模型输出不合法应映射为 422，提示用户改写输入")
	void chat_whenInvalidOutput_shouldReturn422() throws Exception {
		when(aiService.chat(anyLong(), anyString()))
				.thenThrow(new AiException(AiErrorCode.INVALID_OUTPUT, "模型输出不是合法 JSON"));

		mockMvc.perform(post("/api/ai/chat").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"message":"你好"}
								"""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("AI_INVALID_OUTPUT"));
	}

	@Test
	@DisplayName("任务草稿接口应返回 200（草稿不是资源，不返回 201）")
	void taskDraft_shouldReturn200() throws Exception {
		when(aiService.draftTask(anyLong(), anyString())).thenReturn(new TaskDraftResponse("写周报", null, 3, null,
				List.of(), 1, meta()));

		mockMvc.perform(post("/api/ai/task-draft").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"text":"明天写周报"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("写周报"))
				.andExpect(jsonPath("$.data.priority").value(3));
	}

	@Test
	@DisplayName("分类结果应是白名单内的枚举名")
	void classify_shouldReturnWhitelistedCategory() throws Exception {
		when(aiService.classify(anyLong(), anyString()))
				.thenReturn(new ClassifyResponse("BILLING", "涉及账单", meta()));

		mockMvc.perform(post("/api/ai/classify").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"text":"账单不对"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.category").value("BILLING"));
	}

	@Test
	@DisplayName("SSE 流式接口应按 delta → done 的顺序推送事件")
	void stream_shouldEmitDeltaThenDone() throws Exception {
		doAnswer(invocation -> {
			Consumer<String> onDelta = invocation.getArgument(2);
			Consumer<AiChatResult> onComplete = invocation.getArgument(3);
			onDelta.accept("你");
			onDelta.accept("好");
			onComplete.accept(new AiChatResult("你好", "chat", "mock", "mock-rule-v1", 1, 1, 8, 1, "trace-1"));
			return null;
		}).when(aiService).streamChat(anyLong(), anyString(), any(), any(), any());

		MvcResult result = mockMvc.perform(post("/api/ai/chat/stream").with(loginAs(USER_ID))
						.accept("text/event-stream")
						.contentType("application/json").content("""
								{"message":"你好"}
								"""))
				.andExpect(request().asyncStarted())
				.andReturn();

		mockMvc.perform(asyncDispatch(result))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("trace-1")));
	}

	@Test
	@DisplayName("流式接口出错时应发 error 事件，而不是改状态码")
	void stream_whenError_shouldEmitErrorEvent() throws Exception {
		doAnswer(invocation -> {
			Consumer<AiException> onError = invocation.getArgument(4);
			onError.accept(new AiException(AiErrorCode.TIMEOUT, "超时"));
			return null;
		}).when(aiService).streamChat(anyLong(), anyString(), any(), any(), any());

		MvcResult result = mockMvc.perform(post("/api/ai/chat/stream").with(loginAs(USER_ID))
						.accept("text/event-stream")
						.contentType("application/json").content("""
								{"message":"你好"}
								"""))
				.andExpect(request().asyncStarted())
				.andReturn();

		mockMvc.perform(asyncDispatch(result))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("AI_TIMEOUT")));
	}

	@Test
	@DisplayName("查询调用记录应只返回自己的数据")
	void listCalls_shouldReturnOwnRecords() throws Exception {
		when(aiCallLogService.page(USER_ID, 1, 20)).thenReturn(List.of());

		mockMvc.perform(get("/api/ai/calls").with(loginAs(USER_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray());
	}

	@Test
	@DisplayName("查别人的调用记录应返回 404")
	void getCall_notOwner_shouldReturn404() throws Exception {
		when(aiCallLogService.findForOwner(99L, USER_ID)).thenReturn(null);

		mockMvc.perform(get("/api/ai/calls/99").with(loginAs(USER_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

}
