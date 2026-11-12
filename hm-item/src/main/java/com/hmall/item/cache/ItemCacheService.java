package com.hmall.item.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.api.dto.ItemDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品详情 Redis 缓存。
 * <p>
 * key 设计：item:detail:{id}
 * value：ItemDTO 的 JSON；商品不存在时写入 "NULL" 占位值（空值缓存），防止同一个不存在的 id 反复穿透。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheService {

    public static final String DETAIL_KEY_PREFIX = "item:detail:";
    private static final String NULL_VALUE = "NULL";
    /** 基础过期时间 30 分钟，再加 0~60 秒随机值，避免大量 key 同时失效造成缓存雪崩 */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);
    private static final Duration NULL_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读取缓存，区分"未命中"和"命中空值"两种情况
     */
    public Hit get(Long id) {
        String key = DETAIL_KEY_PREFIX + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Hit.miss();
        }
        if (NULL_VALUE.equals(json)) {
            return Hit.empty();
        }
        try {
            return Hit.hit(objectMapper.readValue(json, ItemDTO.class));
        } catch (Exception e) {
            log.warn("商品缓存反序列化失败，key={}", key, e);
            return Hit.miss();
        }
    }

    public void put(ItemDTO item) {
        if (item == null || item.getId() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(item);
            redisTemplate.opsForValue().set(DETAIL_KEY_PREFIX + item.getId(), json, randomTtl());
        } catch (Exception e) {
            log.warn("商品缓存写入失败，id={}", item.getId(), e);
        }
    }

    /**
     * 缓存空值，防止缓存穿透
     */
    public void putNull(Long id) {
        redisTemplate.opsForValue().set(DETAIL_KEY_PREFIX + id, NULL_VALUE, NULL_TTL);
    }

    public void evict(Long id) {
        if (id != null) {
            redisTemplate.delete(DETAIL_KEY_PREFIX + id);
        }
    }

    /**
     * 批量读缓存（购物车、下单时按 id 批量取商品），返回命中的商品，未命中的由调用方查库后自行回填
     */
    public Map<Long, ItemDTO> getBatch(Collection<Long> ids) {
        Map<Long, ItemDTO> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Long> idList = new ArrayList<>(ids);
        List<String> keys = new ArrayList<>(idList.size());
        for (Long id : idList) {
            keys.add(DETAIL_KEY_PREFIX + id);
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return result;
        }
        for (int i = 0; i < values.size() && i < idList.size(); i++) {
            String json = values.get(i);
            if (json == null || NULL_VALUE.equals(json)) {
                continue;
            }
            try {
                result.put(idList.get(i), objectMapper.readValue(json, ItemDTO.class));
            } catch (Exception e) {
                log.warn("商品缓存批量反序列化失败，id={}", idList.get(i), e);
            }
        }
        return result;
    }

    public void putBatch(Collection<ItemDTO> items) {
        if (items == null) {
            return;
        }
        for (ItemDTO item : items) {
            put(item);
        }
    }

    private Duration randomTtl() {
        return BASE_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(60));
    }

    /**
     * 缓存查询结果：未命中 / 命中空值 / 命中商品
     */
    @Getter
    public static class Hit {
        private final boolean hit;
        private final ItemDTO item;

        private Hit(boolean hit, ItemDTO item) {
            this.hit = hit;
            this.item = item;
        }

        public static Hit miss() {
            return new Hit(false, null);
        }

        public static Hit empty() {
            return new Hit(true, null);
        }

        public static Hit hit(ItemDTO item) {
            return new Hit(true, item);
        }
    }
}
