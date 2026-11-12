package com.hmall.api.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * Feign 统一配置：把当前请求的登录身份透传给下游服务。
 * <p>网关校验完 JWT 后写入了 {@code X-User-Id}，如果 Feign 不带这个头，
 * 下游服务就取不到 userId，会出现"远程调用返回空/业务报未登录"的静默失败。</p>
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor userHeaderRelayInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                ServletRequestAttributes attributes =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes == null) {
                    // 异步线程（MQ 消费、定时任务）没有请求上下文，由调用方显式传参或自行兜底
                    return;
                }
                HttpServletRequest request = attributes.getRequest();
                String userId = request.getHeader("X-User-Id");
                if (userId != null && !userId.isBlank()) {
                    template.header("X-User-Id", userId);
                }
                String authorization = request.getHeader("authorization");
                if (authorization != null && !authorization.isBlank()) {
                    template.header("authorization", authorization);
                }
            }
        };
    }
}
