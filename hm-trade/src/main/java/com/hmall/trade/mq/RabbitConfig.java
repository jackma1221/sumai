package com.hmall.trade.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 交易服务的 MQ 拓扑（只声明交易自己消费/发送的队列，商品服务的队列由商品服务声明）：
 * <pre>
 * order.exchange
 *   ├─ order.delay.queue    (order.delay)   无消费者，TTL 到期后死信转发到 order.cancel
 *   └─ order.cancel.queue   (order.cancel)  交易服务消费：关闭超时订单，再发 order.cancelled
 * order.cancelled 由商品服务消费，用于回滚库存。
 * </pre>
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_CREATE_QUEUE = "order.create.queue";
    public static final String ORDER_CREATE_KEY = "order.create";
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    public static final String ORDER_DELAY_KEY = "order.delay";
    public static final String ORDER_CANCEL_QUEUE = "order.cancel.queue";
    public static final String ORDER_CANCEL_KEY = "order.cancel";
    public static final String ORDER_CANCELLED_KEY = "order.cancelled";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable(ORDER_CANCEL_QUEUE).build();
    }

    @Bean
    public Binding orderCancelBinding(Queue orderCancelQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCancelQueue).to(orderExchange).with(ORDER_CANCEL_KEY);
    }

    /**
     * 延迟队列：本身没有消费者，消息在队列里停留 TTL 后按死信路由转发到 order.cancel.queue。
     * TTL 由配置 hm.order.delay-ttl-ms 控制（默认 15 分钟，本地演示调成 60 秒）。
     */
    @Bean
    public Queue orderDelayQueue(@Value("${hm.order.delay-ttl-ms:900000}") long delayTtl) {
        return QueueBuilder.durable(ORDER_DELAY_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_CANCEL_KEY)
                .withArgument("x-message-ttl", delayTtl)
                .build();
    }

    @Bean
    public Binding orderDelayBinding(Queue orderDelayQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderDelayQueue).to(orderExchange).with(ORDER_DELAY_KEY);
    }

    /** 消息统一用 JSON 序列化，便于在控制台直接阅读消息内容 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
