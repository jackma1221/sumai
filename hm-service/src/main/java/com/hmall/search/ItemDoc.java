package com.hmall.search;

import lombok.Data;

/**
 * 商品 ES 文档，与 MySQL item 表字段一一对应。
 * 不直接复用 Item 实体，避免把 createTime/updater 等无关字段写进索引。
 */
@Data
public class ItemDoc {
    /** 商品id */
    private Long id;
    /** SKU 名称（全文检索字段） */
    private String name;
    /** 价格，单位分 */
    private Integer price;
    /** 库存 */
    private Integer stock;
    /** 图片 */
    private String image;
    /** 类目 */
    private String category;
    /** 品牌 */
    private String brand;
    /** 规格，JSON 字符串 */
    private String spec;
    /** 销量 */
    private Integer sold;
    /** 评论数 */
    private Integer commentCount;
    /** 是否广告位 */
    private Boolean isAD;
    /** 状态：1 正常，2 下架，3 删除 */
    private Integer status;
}
