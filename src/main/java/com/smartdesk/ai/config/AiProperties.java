package com.smartdesk.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模块配置（Week3 / Day16）。
 *
 * <p><b>为什么把这些参数做成配置，而不是写成常量？</b>
 * <ul>
 *   <li>{@code max-attempts} / {@code retry-base-millis}：线上要能按模型服务商调整，
 *       改配置重启即可，不用重新发版</li>
 *   <li>{@code request-timeout}：不同模型的 P99 差好几倍，写死一定出事</li>
 *   <li>{@code provider}：本地/测试用 mock，生产用 openai，<b>业务代码零改动</b></li>
 * </ul>
 *
 * <p><b>面试可讲：模型参数（temperature / max-tokens）属于"请求级配置"，
 * 而超时、重试次数属于"治理级配置"。</b>前者往往随场景变化（摘要要稳定，
 * 头脑风暴要发散），后者是全局策略。混在一起放是不专业的。
 */
@ConfigurationProperties(prefix = "smartdesk.ai")
public class AiProperties {

	/** 模型提供方：{@code mock} 或 {@code openai}（任何 OpenAI 兼容协议的服务）。 */
	private String provider = "mock";

	/** 模型名称，透传给服务商。 */
	private String model = "gpt-4o-mini";

	/** 采样温度，0 最确定、1 最发散。 */
	private double temperature = 0.3;

	/** 单次模型调用超时。 */
	private Duration requestTimeout = Duration.ofSeconds(20);

	/** 最多尝试次数（含首次），必须 >= 1。 */
	private int maxAttempts = 3;

	/** 指数退避基数（毫秒）。 */
	private long retryBaseMillis = 300;

	/** 结构化输出校验失败后，最多补问几次。 */
	private int maxRepairAttempts = 1;

	/** mock Provider 流式分片间隔，方便肉眼观察 SSE。 */
	private long mockChunkDelayMillis = 60;

	private final OpenAi openai = new OpenAi();

	private final Mock mock = new Mock();

	private final Agent agent = new Agent();

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public long getRetryBaseMillis() {
		return retryBaseMillis;
	}

	public void setRetryBaseMillis(long retryBaseMillis) {
		this.retryBaseMillis = retryBaseMillis;
	}

	public int getMaxRepairAttempts() {
		return maxRepairAttempts;
	}

	public void setMaxRepairAttempts(int maxRepairAttempts) {
		this.maxRepairAttempts = maxRepairAttempts;
	}

	public long getMockChunkDelayMillis() {
		return mockChunkDelayMillis;
	}

	public void setMockChunkDelayMillis(long mockChunkDelayMillis) {
		this.mockChunkDelayMillis = mockChunkDelayMillis;
	}

	public OpenAi getOpenai() {
		return openai;
	}

	public Mock getMock() {
		return mock;
	}

	public Agent getAgent() {
		return agent;
	}

	/** 是否使用真实模型。 */
	public boolean useOpenAi() {
		return "openai".equalsIgnoreCase(provider);
	}

	/**
	 * OpenAI 兼容协议配置。
	 *
	 * <p>为什么叫 "OpenAI 兼容"而不是 "OpenAI"？
	 * 因为 DeepSeek、通义千问兼容模式、Moonshot、本地 vLLM/Ollama 都实现了
	 * {@code POST /chat/completions} 这套协议。一份代码配不同 base-url 即可切换，
	 * 这也是面试里"如何做模型无关设计"的标准答案。
	 */
	public static class OpenAi {

		/** 形如 {@code https://api.openai.com/v1}，不要带结尾斜杠。 */
		private String baseUrl = "https://api.openai.com/v1";

		/** API Key，只从环境变量注入，绝不落库、绝不写进代码。 */
		private String apiKey = "";

		/** 服务商是否支持 {@code response_format={"type":"json_object"}}。 */
		private boolean supportsJsonMode = false;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public boolean isSupportsJsonMode() {
			return supportsJsonMode;
		}

		public void setSupportsJsonMode(boolean supportsJsonMode) {
			this.supportsJsonMode = supportsJsonMode;
		}

	}

	/**
	 * Agent（工具调用）治理配置（Week4 / Day26）。
	 *
	 * <p><b>这一组参数全部是"防止烧钱"和"防止卡死"的刹车</b>，一个都不能省：
	 * <ul>
	 *   <li>{@code max-rounds}：模型 ↔ 工具的最大往返轮数。没有它，模型可能
	 *       反复调工具永不收敛，一次请求就能烧掉几十次调用</li>
	 *   <li>{@code max-tool-calls-per-round}：单轮最多执行几个工具。模型偶尔会
	 *       一次返回十几个 tool_calls（并行调用），全执行就是十几倍的数据库压力</li>
	 *   <li>{@code total-timeout}：整个 Agent 循环的总预算。单次模型调用有超时，
	 *       但 4 轮 × 20s 仍然是 80s，用户早就关页面了</li>
	 *   <li>{@code duplicate-call-threshold}：同一工具 + 同一参数连续出现 N 次就熔断。
	 *       这是"模型陷入死循环"最典型的形态</li>
	 * </ul>
	 *
	 * <p>面试可讲：这些值不是拍脑袋的。轮数取 4 是因为常见任务链是
	 * "查列表 → 查详情 → 创建"，留一轮余量；再多的链路应该拆成多次对话。
	 */
	public static class Agent {

		/** 模型 ↔ 工具的最大往返轮数（含最后一次纯文本回答）。 */
		private int maxRounds = 4;

		/** 单轮最多执行几个工具调用。 */
		private int maxToolCallsPerRound = 5;

		/** 整个 Agent 循环的总时间预算。 */
		private Duration totalTimeout = Duration.ofSeconds(60);

		/** 待确认草稿的存活时间。 */
		private Duration confirmationTtl = Duration.ofMinutes(5);

		/** 注入模型的历史消息条数上限（按条数，不按 token）。 */
		private int maxHistoryMessages = 20;

		/** 同一工具 + 同一参数连续出现多少次判定为死循环。 */
		private int duplicateCallThreshold = 3;

		public int getMaxRounds() {
			return maxRounds;
		}

		public void setMaxRounds(int maxRounds) {
			this.maxRounds = maxRounds;
		}

		public int getMaxToolCallsPerRound() {
			return maxToolCallsPerRound;
		}

		public void setMaxToolCallsPerRound(int maxToolCallsPerRound) {
			this.maxToolCallsPerRound = maxToolCallsPerRound;
		}

		public Duration getTotalTimeout() {
			return totalTimeout;
		}

		public void setTotalTimeout(Duration totalTimeout) {
			this.totalTimeout = totalTimeout;
		}

		public Duration getConfirmationTtl() {
			return confirmationTtl;
		}

		public void setConfirmationTtl(Duration confirmationTtl) {
			this.confirmationTtl = confirmationTtl;
		}

		public int getMaxHistoryMessages() {
			return maxHistoryMessages;
		}

		public void setMaxHistoryMessages(int maxHistoryMessages) {
			this.maxHistoryMessages = maxHistoryMessages;
		}

		public int getDuplicateCallThreshold() {
			return duplicateCallThreshold;
		}

		public void setDuplicateCallThreshold(int duplicateCallThreshold) {
			this.duplicateCallThreshold = duplicateCallThreshold;
		}

	}

	/** mock Provider 配置。 */
	public static class Mock {

		/** 模拟网络延迟，用来观察耗时统计；测试里设 0。 */
		private long latencyMillis = 80;

		public long getLatencyMillis() {
			return latencyMillis;
		}

		public void setLatencyMillis(long latencyMillis) {
			this.latencyMillis = latencyMillis;
		}

	}

}
