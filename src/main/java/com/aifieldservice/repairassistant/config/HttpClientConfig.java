package com.aifieldservice.repairassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 外部 HTTP 客户端的公共工厂。
 *
 * <p>OpenAI 与 Qdrant 各自在自己的 Gateway 中补充 base URL 和鉴权头，
 * 这里不创建全局单例 RestClient，避免两个系统的配置相互污染。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        // Builder 可以安全地按不同外部系统继续派生独立 RestClient。
        return RestClient.builder();
    }
}
