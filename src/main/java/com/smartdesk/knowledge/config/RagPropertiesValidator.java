package com.smartdesk.knowledge.config;

import org.springframework.beans.factory.InitializingBean;

/**
 * RAG 配置校验器（Week5 / Day29）。
 *
 * <p><b>为什么要在启动时校验，而不是在第一次导入文档时？</b>
 * 因为 {@code chunk-overlap >= chunk-size} 这种配置错误在运行期表现为
 * "导入文档时卡住不动"，排查成本极高（得先怀疑是死循环还是锁表）。
 * 启动时直接失败并给出人话提示，问题暴露得越早越好。
 */
public class RagPropertiesValidator implements InitializingBean {

	private final RagProperties properties;

	public RagPropertiesValidator(RagProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		String error = properties.validate();
		if (error != null) {
			throw new IllegalStateException(error);
		}
	}

}
