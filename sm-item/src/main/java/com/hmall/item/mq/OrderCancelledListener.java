package com.hmall.item.mq;

import com.hmall.api.mq.OrderMessage;
import com.hmall.item.cache.ItemCacheService;
import com.hmall.item.domain.po.ItemStockLog;
import com.hmall.item.mapper.ItemMapper;
import com.hmall.item.mapper.ItemStockLogMapper;
import com.hmall.item.search.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单关闭消息消费端（商品服务）：回滚库存 + 清缓存 + 同步 ES。
 * 只有交易服务确认订单已关闭才会发这条消息，已支付订单不会触发，因此不会误加库存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelledListener {

    private final ItemMapper itemMapper;
    private final ItemStockLogMapper stockLogMapper;
    private final ItemCacheService cacheService;
    private final ItemIndexService itemIndexService;

    @RabbitListener(queues = RabbitConfig.ORDER_CANCELLED_QUEUE)
    public void onOrderCancelled(OrderMessage message) {
        if (message == null || message.getItems() == null) {
            return;
        }
        log.info("[MQ] 收到订单关闭消息，orderId={}，开始回滚库存", message.getOrderId());
        for (OrderMessage.ItemNum item : message.getItems()) {
            try {
                itemMapper.restoreStock(item.getItemId(), item.getNum());
                // 回滚也记一条流水，和下单扣减对应起来方便对账
                stockLogMapper.insert(new ItemStockLog()
                        .setItemId(item.getItemId())
                        .setChangeNum(item.getNum())
                        .setChangeType("restore")
                        .setRemark("订单关闭回滚，orderId=" + message.getOrderId()));
                cacheService.evict(item.getItemId());
                itemIndexService.syncById(item.getItemId());
            } catch (Exception e) {
                log.warn("[MQ] 回滚库存失败，itemId={}，原因={}", item.getItemId(), e.getMessage());
            }
        }
    }
}
