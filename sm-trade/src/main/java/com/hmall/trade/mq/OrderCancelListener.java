package com.hmall.trade.mq;

import com.hmall.api.mq.OrderMessage;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderStatusLog;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 延迟消息消费端（交易服务）：订单超时未支付则关闭订单，然后发 order.cancelled 通知商品服务回滚库存。
 * <p>状态判断保证幂等：已支付（status=2）或已关闭（status=5）的订单直接忽略。
 * 库存回滚不在本地做，而是交给商品服务，避免交易服务直接改商品库。</p>
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
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final OrderEventProducer orderEventProducer;

    @RabbitListener(queues = RabbitConfig.ORDER_CANCEL_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void onOrderCancel(OrderMessage message) {
        if (message == null || message.getOrderId() == null) {
            return;
        }
        Order order = orderMapper.selectById(message.getOrderId());
        if (order == null || order.getStatus() == null || order.getStatus() != STATUS_UNPAID) {
            log.info("[MQ] 订单 {} 当前状态 {}，无需取消", message.getOrderId(),
                    order == null ? "不存在" : order.getStatus());
            return;
        }
        // 1. 关闭订单
        Order update = new Order().setId(order.getId()).setStatus(STATUS_CLOSED).setCloseTime(LocalDateTime.now());
        orderMapper.updateById(update);
        // 状态流转留痕：1 -> 5（超时关闭）
        orderStatusLogMapper.insert(new OrderStatusLog()
                .setOrderId(order.getId())
                .setFromStatus(STATUS_UNPAID)
                .setToStatus(STATUS_CLOSED)
                .setRemark("超时未支付自动关闭"));
        // 2. 通知商品服务回滚库存（事务提交后发送，避免订单回滚但库存已加）
        orderEventProducer.sendOrderCancelled(message);
        log.info("[MQ] 订单 {} 超时未支付已自动取消，已通知商品服务回滚库存", order.getId());
    }
}
