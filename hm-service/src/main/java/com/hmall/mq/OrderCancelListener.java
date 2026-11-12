package com.hmall.mq;

import com.hmall.cache.ItemCacheService;
import com.hmall.domain.po.Order;
import com.hmall.mapper.ItemMapper;
import com.hmall.mapper.OrderMapper;
import com.hmall.search.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 延迟消息消费端：订单超时未支付则自动取消，并回滚库存。
 * 消费前先判断订单状态，保证重复投递时不会重复回滚（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelListener {

    /** 订单状态：未付款 */
    private static final int STATUS_UNPAID = 1;
    /** 订单状态：交易取消，订单关闭 */
    private static final int STATUS_CLOSED = 5;

    private final OrderMapper orderMapper;
    private final ItemMapper itemMapper;
    private final ItemCacheService cacheService;
    private final ItemIndexService itemIndexService;

    @RabbitListener(queues = RabbitConfig.ORDER_CANCEL_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void onOrderCancel(OrderMessage message) {
        if (message == null || message.getOrderId() == null) {
            return;
        }
        Order order = orderMapper.selectById(message.getOrderId());
        if (order == null || order.getStatus() == null || order.getStatus() != STATUS_UNPAID) {
            // 已支付或已取消：说明用户已付款/已处理，直接忽略，保证幂等
            log.info("[MQ] 订单 {} 当前状态 {}，无需取消", message.getOrderId(), order == null ? "不存在" : order.getStatus());
            return;
        }
        // 1. 关闭订单
        Order update = new Order().setId(order.getId()).setStatus(STATUS_CLOSED).setCloseTime(LocalDateTime.now());
        orderMapper.updateById(update);
        // 2. 回滚库存 + 清缓存 + 同步 ES
        if (message.getItems() != null) {
            for (OrderMessage.ItemNum item : message.getItems()) {
                itemMapper.restoreStock(item.getItemId(), item.getNum());
                cacheService.evict(item.getItemId());
                itemIndexService.syncById(item.getItemId());
            }
        }
        log.info("[MQ] 订单 {} 超时未支付已自动取消，回滚 {} 个商品库存", order.getId(), message.getItems() == null ? 0 : message.getItems().size());
    }
}
