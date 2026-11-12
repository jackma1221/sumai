package com.hmall.trade.remote;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.exception.BizIllegalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 商品服务远程调用包装层：Feign 调用 + Sentinel 熔断降级。
 * <p>把远程调用集中在这里，好处是熔断点清晰：商品服务挂了，交易服务的下单链路直接走兜底，
 * 不会因为大量超时把交易服务的线程池占满。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemRemoteService {

    private final ItemClient itemClient;

    @SentinelResource(value = "remoteItemQuery", blockHandler = "queryBlock")
    public ItemDTO queryItemById(Long id) {
        return itemClient.queryItemById(id);
    }

    @SentinelResource(value = "remoteItemQuery", blockHandler = "queryBlock")
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        return itemClient.queryItemByIds(ids);
    }

    public ItemDTO queryBlock(Long id, BlockException ex) {
        log.warn("[熔断] 查询商品被降级，itemId={}，原因={}", id, ex.getClass().getSimpleName());
        throw new BizIllegalException("商品服务暂时不可用，已触发熔断降级，请稍后重试");
    }

    public List<ItemDTO> queryBlock(Collection<Long> ids, BlockException ex) {
        log.warn("[熔断] 批量查询商品被降级，数量={}，原因={}", ids == null ? 0 : ids.size(), ex.getClass().getSimpleName());
        throw new BizIllegalException("商品服务暂时不可用，已触发熔断降级，请稍后重试");
    }

    @SentinelResource(value = "remoteItemDeduct", blockHandler = "deductBlock")
    public void deductStock(List<OrderDetailDTO> items) {
        itemClient.deductStock(items);
    }

    public void deductBlock(List<OrderDetailDTO> items, BlockException ex) {
        log.warn("[熔断] 扣减库存被降级，商品数={}，原因={}", items == null ? 0 : items.size(), ex.getClass().getSimpleName());
        throw new BizIllegalException("商品服务暂时不可用，已触发熔断降级，请稍后重试");
    }
}
