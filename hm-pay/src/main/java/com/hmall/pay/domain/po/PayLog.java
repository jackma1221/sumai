package com.hmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 支付流水（第 11 张表）：每笔成功支付落一条流水，用于对账。
 */
@Data
@Accessors(chain = true)
@TableName("pay_log")
public class PayLog {

    private Long id;
    private Long payOrderId;
    private Long bizOrderNo;
    private Integer amount;
    private String channel;
    private String status;
    private LocalDateTime createTime;
}
