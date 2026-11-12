package com.hmall.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.utils.BeanUtils;
import com.hmall.domain.dto.ItemDTO;
import com.hmall.domain.po.Item;
import com.hmall.domain.query.ItemPageQuery;
import com.hmall.search.ItemDoc;
import com.hmall.search.ItemIndexService;
import com.hmall.search.ItemSearchResult;
import com.hmall.service.IItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品检索接口：
 * 1. /search/items 走 ES，返回高亮片段与品牌/类目聚合，供筛选面板使用；
 * 2. /search/list 兼容原前端分页结构，ES 不可用时自动降级为 MySQL 模糊查询；
 * 3. /search/rebuild 手动触发全量重建索引。
 */
@Slf4j
@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final IItemService itemService;
    private final ItemIndexService itemIndexService;

    @ApiOperation("商品搜索（ES：关键字+多维过滤+价格排序+高亮+品牌聚合）")
    @GetMapping("/items")
    public ItemSearchResult searchItems(ItemPageQuery query) throws Exception {
        return itemIndexService.search(query);
    }

    @ApiOperation("搜索商品（兼容原分页返回结构）")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        try {
            ItemSearchResult result = itemIndexService.search(query);
            List<ItemDTO> list = result.getItems().stream()
                    .map(hit -> BeanUtils.copyBean(hit.getItem(), ItemDTO.class))
                    .collect(Collectors.toList());
            return new PageDTO<>(result.getTotal(), result.getTotalPages(), list);
        } catch (Exception e) {
            // ES 异常时降级走数据库，保证搜索接口可用
            log.warn("ES 检索失败，降级为数据库查询：{}", e.getMessage());
            return searchByDb(query);
        }
    }

    @ApiOperation("全量重建商品索引")
    @PostMapping("/rebuild")
    public int rebuild() throws Exception {
        itemIndexService.dropIndex();
        return itemIndexService.rebuildAll();
    }

    private PageDTO<ItemDTO> searchByDb(ItemPageQuery query) {
        Page<Item> result = itemService.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .page(query.toMpPage("update_time", false));
        return PageDTO.of(result, ItemDTO.class);
    }
}
