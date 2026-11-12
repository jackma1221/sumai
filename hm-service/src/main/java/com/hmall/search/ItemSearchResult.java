package com.hmall.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商品搜索结果：命中商品 + 高亮片段 + 品牌/类目聚合（供前端做筛选项）。
 */
@Data
public class ItemSearchResult {
    /** 命中总数 */
    private long total;
    private int pageNo;
    private int pageSize;
    private long totalPages;
    /** 商品列表 */
    private List<ItemHit> items;
    /** 品牌聚合 */
    private List<Facet> brands;
    /** 类目聚合 */
    private List<Facet> categories;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemHit {
        private ItemDoc item;
        /** 名称高亮片段，未命中关键词时为 null */
        private String highlightName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Facet {
        private String name;
        private long count;
    }
}
