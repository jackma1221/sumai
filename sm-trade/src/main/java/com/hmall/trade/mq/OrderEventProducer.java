package com.hmall.trade.mq;

import com.hmall.api.mq.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单消息生产者：只负责发消息，发送时机由调用方控制（事务提交后发送）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendOrderCreated(OrderMessage message) {
        // 1. 立即队列：商品服务异步加销量、同步 ES，不阻塞下单主链路
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CREATE_KEY, message);
        // 2. 延迟队列：TTL 到期后变成 order.cancel 消息，实现超时未支付自动取消
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_KEY, message);
        log.info("[MQ] 下单成功消息已发送，orderId={}", message.getOrderId());
    }

    /**
     * 订单确认关闭后发送，商品服务据此回滚库存。
     * 与"延迟取消"消息分开，是为了保证只有真正被关闭的订单才会回滚库存（已支付订单不会）。
     */
    public void sendOrderCancelled(OrderMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CANCELLED_KEY, message);
        log.info("[MQ] 订单关闭消息已发送，orderId={}", message.getOrderId());
    }
}
