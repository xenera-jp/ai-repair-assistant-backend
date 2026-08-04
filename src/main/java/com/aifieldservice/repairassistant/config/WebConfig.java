package com.aifieldservice.repairassistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 仅为前后端分离场景开放 /api/** 的跨域访问。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RepairAssistantProperties properties;

    public WebConfig(RepairAssistantProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Actuator 等管理端点不在 CORS 开放范围内，减少无意暴露的管理面。
        registry.addMapping("/api/**")
                .allowedOrigins(properties.web().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
