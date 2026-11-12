package com.hmall.mq;

import com.hmall.cache.ItemCacheService;
import com.hmall.mapper.ItemMapper;
import com.hmall.search.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 下单成功消息消费端：异步累加商品销量，并同步清缓存、更新 ES 文档。
 * 这些动作失败不影响订单本身，消费异常只记日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateListener {

    private final ItemMapper itemMapper;
    private final ItemCacheService cacheService;
    private final ItemIndexService itemIndexService;

    @RabbitListener(queues = RabbitConfig.ORDER_CREATE_QUEUE)
    public void onOrderCreated(OrderMessage message) {
        if (message == null || message.getItems() == null) {
            return;
        }
        log.info("[MQ] 收到下单成功消息，orderId={}，商品数={}", message.getOrderId(), message.getItems().size());
        for (OrderMessage.ItemNum item : message.getItems()) {
            try {
                itemMapper.increaseSold(item.getItemId(), item.getNum());
                cacheService.evict(item.getItemId());
                itemIndexService.syncById(item.getItemId());
            } catch (Exception e) {
                log.warn("[MQ] 增加销量失败，itemId={}，原因={}", item.getItemId(), e.getMessage());
            }
        }
    }
}
