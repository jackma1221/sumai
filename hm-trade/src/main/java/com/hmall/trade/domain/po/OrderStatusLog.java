package com.hmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 订单状态流转日志（第 10 张表）：创建、支付、关闭都留痕，便于排查"订单状态对不上"的问题。
 */
@Data
@Accessors(chain = true)
@TableName("order_status_log")
public class OrderStatusLog {

    private Long id;
    private Long orderId;
    private Integer fromStatus;
    private Integer toStatus;
    private String remark;
    private LocalDateTime createTime;
}
