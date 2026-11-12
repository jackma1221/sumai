package com.hmall.gateway.filter;

import com.hmall.common.jwt.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局 JWT 鉴权过滤器（网关统一鉴权）。
 * <pre>
 * 1. 白名单放行：登录、搜索、商品浏览、健康检查；
 * 2. 其余请求必须带 authorization: token，校验通过后把 userId 注入 X-User-Id 请求头；
 * 3. 校验失败直接返回 401 + JSON，不再转发到业务服务。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    /** 无需登录即可访问的路径 */
    private static final List<String> WHITE_LIST = List.of(
            "/users/login",
            "/search/**",
            "/items/**",
            "/hi",
            "/error"
    );

    private final JwtTool jwtTool;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (matchWhiteList(path)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (token == null || token.isBlank()) {
            token = exchange.getRequest().getHeaders().getFirst("authorization");
        }
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Long userId;
        try {
            userId = jwtTool.parseToken(token);
        } catch (Exception e) {
            log.info("[网关鉴权] 拒绝访问 {}：{}", path, e.getMessage());
            return unauthorized(exchange);
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean matchWhiteList(String path) {
        for (String pattern : WHITE_LIST) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        byte[] body = "{\"code\":401,\"msg\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
