package com.hmall.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 启动时初始化商品索引：索引不存在先建索引，索引为空则从 MySQL 全量同步。
 * 异步执行，ES 不可用时只降级检索接口，不阻塞应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemIndexInitializer {

    private final ItemIndexService itemIndexService;

    @PostConstruct
    public void init() {
        Thread thread = new Thread(() -> {
            try {
                itemIndexService.ensureIndex();
                long count = itemIndexService.count();
                if (count > 0) {
                    log.info("ES 商品索引已存在 {} 条，跳过全量同步", count);
                    return;
                }
                int size = itemIndexService.rebuildAll();
                log.info("ES 商品索引初始化完成，共 {} 条", size);
            } catch (Exception e) {
                log.warn("ES 商品索引初始化失败，检索将降级为数据库查询：{}", e.getMessage());
            }
        }, "item-index-init");
        thread.setDaemon(true);
        thread.start();
    }
}
