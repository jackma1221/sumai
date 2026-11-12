package com.hmall.item.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.utils.BeanUtils;
import com.hmall.api.dto.ItemDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.domain.query.ItemPageQuery;
import com.hmall.item.search.ItemDoc;
import com.hmall.item.search.ItemIndexService;
import com.hmall.item.search.ItemSearchResult;
import com.hmall.item.mapper.SearchHotWordMapper;
import com.hmall.item.service.IItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final SearchHotWordMapper hotWordMapper;

    @ApiOperation("商品搜索（ES：关键字+多维过滤+价格排序+高亮+品牌聚合）")
    @GetMapping("/items")
    public ItemSearchResult searchItems(ItemPageQuery query) throws Exception {
        return itemIndexService.search(query);
    }

    @ApiOperation("搜索商品（兼容原分页返回结构）")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        // 搜索热词统计（第 12 张表）：同一个关键词累加一次，供联想与热门推荐使用
        recordHotWord(query.getKey());
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

    @ApiOperation("搜索筛选项（品牌/分类），供门户筛选面板渲染")
    @PostMapping("/filters")
    public Map<String, List<String>> filters(@RequestBody ItemPageQuery query) {
        try {
            return itemIndexService.filterOptions(query);
        } catch (Exception e) {
            log.warn("ES 筛选项查询失败，返回空筛选：{}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @ApiOperation("搜索关键字联想")
    @GetMapping("/suggestion")
    public List<String> suggestion(ItemPageQuery query) {
        try {
            return itemIndexService.suggest(query);
        } catch (Exception e) {
            log.warn("ES 联想查询失败，返回空列表：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 记录搜索热词：走 MySQL 的 ON DUPLICATE KEY UPDATE，异常不影响搜索主流程。
     */
    private void recordHotWord(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return;
        }
        try {
            hotWordMapper.increase(keyword.trim());
        } catch (Exception e) {
            log.warn("记录搜索热词失败，keyword={}，原因={}", keyword, e.getMessage());
        }
    }

    private PageDTO<ItemDTO> searchByDb(ItemPageQuery query) {        Page<Item> result = itemService.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .page(query.toMpPage("update_time", false));
        return PageDTO.of(result, ItemDTO.class);
    }
}
