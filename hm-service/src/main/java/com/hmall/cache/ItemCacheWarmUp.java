package com.hmall.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmall.common.utils.BeanUtils;
import com.hmall.domain.dto.ItemDTO;
import com.hmall.domain.po.Item;
import com.hmall.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 缓存预热：应用启动时在后台线程完成两件事
 * 1. 构建商品布隆过滤器（全量商品 id 写入位图）
 * 2. 把热销商品详情提前写入 Redis，避免启动后第一批请求全部落到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheWarmUp {

    /** 预热的热销商品数量 */
    private static final int PREHEAT_SIZE = 100;

    private final ItemBloomFilter bloomFilter;
    private final ItemCacheService cacheService;
    private final ItemMapper itemMapper;

    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                bloomFilter.rebuildIfAbsent();
                preheat();
                log.info("商品缓存预热完成，耗时={}ms", System.currentTimeMillis() - start);
            } catch (Exception e) {
                // 预热失败只影响性能，不影响功能
                log.error("商品缓存预热失败", e);
            }
        });
    }

    private void preheat() {
        List<Item> hotItems = itemMapper.selectList(new QueryWrapper<Item>()
                .eq("status", 1)
                .orderByDesc("sold")
                .last("limit " + PREHEAT_SIZE));
        if (hotItems.isEmpty()) {
            return;
        }
        List<ItemDTO> dtoList = BeanUtils.copyList(hotItems, ItemDTO.class);
        cacheService.putBatch(dtoList);
        log.info("热销商品详情已预热，数量={}", dtoList.size());
    }
}
