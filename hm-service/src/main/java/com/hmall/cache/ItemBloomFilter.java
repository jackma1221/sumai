package com.hmall.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmall.domain.po.Item;
import com.hmall.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品布隆过滤器（基于 Redis BitMap）。
 * <p>
 * 用 2MB 的位图（1<<24 位）+ 3 个哈希函数记录"一定不存在"的商品 id，
 * 查询商品详情时先过布隆过滤器，可以挡掉大量不存在的 id，避免请求打到 MySQL（缓存穿透）。
 * <p>
 * 说明：布隆过滤器只会误判"可能存在"，不会漏判，所以返回 false 时可以直接认定商品不存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemBloomFilter {

    /** 位图 key */
    public static final String KEY = "bloom:item";
    /** 位图长度：2^24 位 = 2MB，可容纳约 100 万商品（误判率 1% 以内） */
    private static final int BITS = 1 << 24;
    /** 哈希函数个数 */
    private static final int HASHES = 3;
    private static final int BATCH_SIZE = 2000;

    private final StringRedisTemplate redisTemplate;
    private final ItemMapper itemMapper;

    /** 位图是否已经构建完成；未完成时降级为直接查库，保证可用性 */
    private volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    /**
     * 商品 id 是否可能存在
     */
    public boolean mightContain(Long id) {
        if (id == null) {
            return false;
        }
        if (!ready) {
            // 位图还没构建完，不能拦截，直接放行
            return true;
        }
        for (int seed = 0; seed < HASHES; seed++) {
            Boolean bit = redisTemplate.opsForValue().getBit(KEY, bitIndex(id, seed));
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 商品真实存在时，把它的 id 写入位图（避免后续查询被误挡）
     */
    public void put(Long id) {
        if (id == null) {
            return;
        }
        for (int seed = 0; seed < HASHES; seed++) {
            redisTemplate.opsForValue().setBit(KEY, bitIndex(id, seed), true);
        }
    }

    /**
     * 位图不存在时，全量加载商品 id 构建；已存在则直接标记就绪
     */
    public synchronized void rebuildIfAbsent() {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(KEY))) {
                ready = true;
                log.info("商品布隆过滤器已存在，跳过构建");
                return;
            }
            long start = System.currentTimeMillis();
            List<Long> ids = itemMapper.selectObjs(new QueryWrapper<Item>().select("id")).stream()
                    .map(o -> Long.valueOf(o.toString()))
                    .collect(Collectors.toList());
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                batchSetBits(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())));
            }
            ready = true;
            log.info("商品布隆过滤器构建完成，商品数={}，耗时={}ms", ids.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 构建失败不影响主流程，mightContain 会一直返回 true（降级查库）
            log.error("商品布隆过滤器构建失败，已降级为直接查库", e);
        }
    }

    private void batchSetBits(List<Long> ids) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
            for (Long id : ids) {
                for (int seed = 0; seed < HASHES; seed++) {
                    connection.setBit(key, bitIndex(id, seed), true);
                }
            }
            return null;
        });
    }

    /**
     * 第 seed 个哈希函数映射到的位下标，SplitMix64 混合后落到 [0, BITS)
     */
    private long bitIndex(long id, int seed) {
        long z = id + 0x9E3779B97F4A7C15L * (seed + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z & (BITS - 1);
    }
}
