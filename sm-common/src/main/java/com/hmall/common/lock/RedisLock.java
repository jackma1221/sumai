package com.hmall.common.lock;

import com.hmall.common.exception.BizIllegalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 Redis 的分布式锁。
 * <p>
 * 加锁：SET key token NX PX ttl，保证"判断+写入"的原子性；
 * 解锁：Lua 脚本比对 token 后再删除，避免误删其他线程重新加上的锁。</p>
 * <p>注意：这里不加 @Component，由 {@code RedisLockConfig} 在"存在 Redis 依赖"的服务里注册，
 * 避免没有 Redis 依赖的服务（如用户、支付）启动时因为缺少 Redis 类而失败。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisLock {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试加锁，不等待
     *
     * @return 成功返回锁的 token（解锁时必须回传），失败返回 null
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(success) ? token : null;
    }

    /**
     * 释放锁，只有 token 匹配时才删除
     */
    public boolean unlock(String key, String token) {
        if (token == null) {
            return false;
        }
        Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        return result != null && result > 0;
    }

    /**
     * 在锁内执行一段逻辑，拿不到锁时自旋等待，超时抛出业务异常
     *
     * @param key        锁的 key
     * @param ttl        锁的自动释放时间，防止宕机死锁
     * @param waitMillis 最长等待时间
     */
    public <T> T executeWithLock(String key, Duration ttl, long waitMillis, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + waitMillis;
        String token;
        while (true) {
            token = tryLock(key, ttl);
            if (token != null) {
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new BizIllegalException("系统繁忙，请稍后重试");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizIllegalException("操作被中断，请稍后重试");
            }
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }
}
