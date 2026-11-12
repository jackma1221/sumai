package com.hmall.mq;

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
        // 1. 立即队列：异步加销量、发通知，不阻塞下单主链路
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CREATE_KEY, message);
        // 2. 延迟队列：TTL 到期后变成 order.cancel 消息，实现超时未支付自动取消
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_KEY, message);
        log.info("[MQ] 下单成功消息已发送，orderId={}", message.getOrderId());
    }
}
