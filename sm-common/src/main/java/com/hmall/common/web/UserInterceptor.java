package com.hmall.common.web;

import com.hmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 统一读取网关注入的用户身份。
 * <p>网关完成 JWT 校验后，把 userId 写进 {@code X-User-Id} 请求头；
 * 各微服务只信任这个请求头，不再重复解析 token，避免每个服务都要持有私钥。</p>
 */
@Slf4j
public class UserInterceptor implements HandlerInterceptor {

    public static final String USER_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(USER_HEADER);
        if (userId != null && !userId.isBlank()) {
            try {
                UserContext.setUser(Long.valueOf(userId.trim()));
            } catch (NumberFormatException e) {
                log.warn("[鉴权] 非法的 X-User-Id 请求头：{}", userId);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.removeUser();
    }
}
