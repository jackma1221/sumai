package com.hmall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动类：端口 8080（与前端 nginx 的 /api 代理保持一致，拆分后前端无需改动）。
 * 只扫描 com.hmall.gateway 包，避免把各服务的 MyBatis/Redis 配置拉进网关。
 */
@SpringBootApplication(scanBasePackages = "com.hmall.gateway")
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
