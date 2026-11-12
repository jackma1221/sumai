package com.hmall.service;

import com.hmall.domain.dto.ItemDTO;
import com.hmall.domain.dto.OrderDetailDTO;
import com.hmall.domain.po.Item;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IItemService extends IService<Item> {

    void deductStock(List<OrderDetailDTO> items);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    /**
     * 根据 id 查询商品详情（布隆过滤器 + Redis 缓存 + 互斥锁）
     */
    ItemDTO queryItemById(Long id);

    void saveItem(ItemDTO item);

    void updateItem(ItemDTO item);

    void updateItemStatus(Long id, Integer status);

    void deleteItemById(Long id);
}
