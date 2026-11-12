package com.hmall.common.config;

import com.hmall.common.lock.RedisLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式锁装配：只在引入了 Redis 的服务里生效。
 * 用户服务、支付服务没有缓存需求，不带 Redis 依赖，也就不会加载这个 Bean。
 */
@Configuration
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisLockConfig {

    @Bean
    public RedisLock redisLock(StringRedisTemplate redisTemplate) {
        return new RedisLock(redisTemplate);
    }
}
