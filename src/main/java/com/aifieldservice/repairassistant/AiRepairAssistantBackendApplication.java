package com.aifieldservice.repairassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 维修助手服务端启动入口。
 *
 * <p>{@link SpringBootApplication} 负责组件扫描和自动配置，
 * {@link ConfigurationPropertiesScan} 负责把 application.yml 中的
 * {@code repair-assistant.*} 配置绑定到类型安全的配置对象。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiRepairAssistantBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiRepairAssistantBackendApplication.class, args);
	}

}
