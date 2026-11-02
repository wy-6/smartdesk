package com.smartdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SmartDesk 应用入口。
 *
 * <p>Day2 要理解的核心：{@code @SpringBootApplication} = {@code @SpringBootConfiguration}
 * + {@code @EnableAutoConfiguration} + {@code @ComponentScan}。
 * 它会扫描本包及子包下的 {@code @Component/@Service/@Repository/@Controller}，
 * 并根据 classpath 上的依赖自动装配 DataSource、DispatcherServlet 等基础设施。
 */
@SpringBootApplication
public class SmartDeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartDeskApplication.class, args);
	}

}
