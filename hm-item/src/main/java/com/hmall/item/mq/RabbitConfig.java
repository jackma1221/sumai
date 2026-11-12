package com.hmall.item.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品服务消费的 MQ 拓扑：
 * <pre>
 * order.create.queue     <- order.exchange (order.create)     下单成功：异步加销量 + 同步 ES
 * order.cancelled.queue  <- order.exchange (order.cancelled)  订单关闭：回滚库存 + 同步 ES
 * </pre>
 * 交易服务只发消息，商品服务自己声明并消费自己的队列，服务间不共享队列定义。
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_CREATE_QUEUE = "order.create.queue";
    public static final String ORDER_CREATE_KEY = "order.create";
    public static final String ORDER_CANCELLED_QUEUE = "order.cancelled.queue";
    public static final String ORDER_CANCELLED_KEY = "order.cancelled";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE).build();
    }

    @Bean
    public Binding orderCreateBinding(Queue orderCreateQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCreateQueue).to(orderExchange).with(ORDER_CREATE_KEY);
    }

    @Bean
    public Queue orderCancelledQueue() {
        return QueueBuilder.durable(ORDER_CANCELLED_QUEUE).build();
    }

    @Bean
    public Binding orderCancelledBinding(Queue orderCancelledQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCancelledQueue).to(orderExchange).with(ORDER_CANCELLED_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
