package com.aifieldservice.repairassistant.controller.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.service.system.SystemStatusService;
import com.aifieldservice.repairassistant.service.system.SystemStatusService.SystemStatus;

/**
 * 给前端和部署检查使用的轻量状态接口。
 *
 * <p>这里返回的是“是否完成配置”，不是对 OpenAI/Qdrant 的实时连通性探测；
 * 实时健康检查应放在 Actuator HealthIndicator 中，避免页面请求触发外部调用。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final SystemStatusService statusService;

    public SystemStatusController(SystemStatusService statusService) {
        this.statusService = statusService;
    }

    /** 返回服务状态、知识库版本及外部集成的配置状态，不执行外部连通性探测。 */
    @GetMapping("/status")
    public SystemStatus status() {
        return statusService.status();
    }
}
