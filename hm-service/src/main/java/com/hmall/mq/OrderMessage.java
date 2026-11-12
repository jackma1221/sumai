package com.hmall.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下单成功消息体。
 * 时间用毫秒时间戳，避免 Jackson 在 MQ 序列化时缺少 JSR310 模块导致 LocalDateTime 反序列化失败。
 */
@Data
public class OrderMessage {
    private Long orderId;
    private Long userId;
    private Integer totalFee;
    private Long createTime;
    private List<ItemNum> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemNum {
        private Long itemId;
        private Integer num;
    }
}
