package com.smartdesk.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 知识库配置（Week5 / Day29-35）。
 *
 * <p><b>为什么切片参数必须可配置？</b>
 * 这是第 5 周最容易写死、也最容易在面试里被追问的一点：
 * <ul>
 *   <li>切得<b>太小</b>：一个完整的意思被劈成两半，检索到半句话，
 *       模型看到的是残缺上下文，只能靠猜 —— 表现为"召回率上去了但答案不对"</li>
 *   <li>切得<b>太大</b>：一个 chunk 里混了好几个主题，向量被"平均"了，
 *       谁都不像；而且塞进 Prompt 的 token 变多，成本和延迟都上去</li>
 *   <li><b>重叠</b>（overlap）：防止答案正好落在切割线上被劈成两半。
 *       重叠太大 → 存储和检索都浪费；太小 → 切割线上丢信息</li>
 * </ul>
 * 所以这两个值必须是配置项：不同文档类型（制度 vs 手册 vs 表格）需要不同策略，
 * 而且要能通过 A/B 对比来选（第 5 周评测任务就是干这个的）。
 *
 * <p><b>面试可讲：</b>切分策略不是"调参调出来的玄学"，而是可以量化评估的 ——
 * 用固定评测集比较不同 chunk-size / overlap 下的 hit@k 和引用正确率，
 * 用数据选参数，而不是拍脑袋。
 */
@ConfigurationProperties(prefix = "smartdesk.rag")
public class RagProperties {

	/** 是否启用 RAG 模块。关闭后检索接口直接返回明确的业务错误，不做静默降级。 */
	private boolean enabled = true;

	/** 切片长度（字符数，不是 token 数）。中文场景 300–500 字符通常是一个自然段。 */
	private int chunkSize = 400;

	/** 相邻切片的重叠字符数。必须小于 chunkSize，否则会死循环。 */
	private int chunkOverlap = 80;

	/** 过短的尾巴片段直接并入上一片，避免产生只有一句话的碎片。 */
	private int minChunkSize = 40;

	/** 默认召回片段数（Top-K）。 */
	private int topK = 5;

	/** 允许调用方传入的最大 Top-K，防止有人传 topK=10000 拖垮数据库。 */
	private int maxTopK = 20;

	/**
	 * 相似度阈值：低于这个分数的片段不返回。
	 *
	 * <p>为什么需要阈值？因为 Top-K 一定有 K 个结果，哪怕一个都不相关，
	 * 强行塞进 Prompt 会诱导模型编造。宁可说"没查到"，也不要给一堆噪音。
	 */
	private double similarityThreshold = 0.05;

	/** 单个文档的最大字符数，防止把整本书塞进数据库。 */
	private int maxDocumentChars = 100_000;

	/** 检索时每份文档最多参与多少个片段（防止一份超长文档霸榜）。 */
	private int maxChunksPerDocument = 50;

	private final Embedding embedding = new Embedding();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(int chunkSize) {
		this.chunkSize = chunkSize;
	}

	public int getChunkOverlap() {
		return chunkOverlap;
	}

	public void setChunkOverlap(int chunkOverlap) {
		this.chunkOverlap = chunkOverlap;
	}

	public int getMinChunkSize() {
		return minChunkSize;
	}

	public void setMinChunkSize(int minChunkSize) {
		this.minChunkSize = minChunkSize;
	}

	public int getTopK() {
		return topK;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}

	public int getMaxTopK() {
		return maxTopK;
	}

	public void setMaxTopK(int maxTopK) {
		this.maxTopK = maxTopK;
	}

	public double getSimilarityThreshold() {
		return similarityThreshold;
	}

	public void setSimilarityThreshold(double similarityThreshold) {
		this.similarityThreshold = similarityThreshold;
	}

	public int getMaxDocumentChars() {
		return maxDocumentChars;
	}

	public void setMaxDocumentChars(int maxDocumentChars) {
		this.maxDocumentChars = maxDocumentChars;
	}

	public int getMaxChunksPerDocument() {
		return maxChunksPerDocument;
	}

	public void setMaxChunksPerDocument(int maxChunksPerDocument) {
		this.maxChunksPerDocument = maxChunksPerDocument;
	}

	public Embedding getEmbedding() {
		return embedding;
	}

	/**
	 * 启动时校验配置，避免"上线才发现 overlap >= chunkSize 导致死循环"。
	 *
	 * @return 配置错误描述；无错误返回 null
	 */
	public String validate() {
		if (chunkSize <= 0) {
			return "smartdesk.rag.chunk-size 必须大于 0";
		}
		if (chunkOverlap < 0) {
			return "smartdesk.rag.chunk-overlap 不能为负数";
		}
		if (chunkOverlap >= chunkSize) {
			return "smartdesk.rag.chunk-overlap 必须小于 chunk-size（否则切片会死循环）";
		}
		if (minChunkSize < 0 || minChunkSize >= chunkSize) {
			return "smartdesk.rag.min-chunk-size 必须小于 chunk-size";
		}
		if (topK <= 0 || topK > maxTopK) {
			return "smartdesk.rag.top-k 必须在 1.." + maxTopK + " 之间";
		}
		if (similarityThreshold < 0 || similarityThreshold > 1) {
			return "smartdesk.rag.similarity-threshold 必须在 0..1 之间";
		}
		return null;
	}

	/**
	 * Embedding 配置。
	 *
	 * <p><b>Embedding 模型和聊天模型是两回事</b>：聊天模型生成文本，
	 * embedding 模型把文本变成一串数字（向量），用来算"像不像"。
	 * 它们的维度、计费方式、调用协议都不同。
	 */
	public static class Embedding {

		/** 嵌入提供方：{@code mock}（离线确定性哈希）或 {@code openai}（兼容 /v1/embeddings）。 */
		private String provider = "mock";

		/** 嵌入模型名，透传给服务商。 */
		private String model = "text-embedding-v3";

		/** 向量维度。mock 固定 256；真实模型按服务商（百炼 v3 是 1024/2048 可选）。 */
		private int dimensions = 256;

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

		public int getDimensions() {
			return dimensions;
		}

		public void setDimensions(int dimensions) {
			this.dimensions = dimensions;
		}

		public boolean useOpenAi() {
			return "openai".equalsIgnoreCase(provider);
		}

	}

}
