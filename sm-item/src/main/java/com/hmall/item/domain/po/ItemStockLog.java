package com.hmall.item.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 商品库存变更流水（第 9 张表）：每次扣减/回滚库存都落一条记录，方便排查超卖与对账。
 */
@Data
@Accessors(chain = true)
@TableName("item_stock_log")
public class ItemStockLog {

    private Long id;
    private Long itemId;
    /** 库存变化量，扣减为负，回滚为正 */
    private Integer changeNum;
    /** deduct=下单扣减，restore=订单关闭回滚 */
    private String changeType;
    private String remark;
    private LocalDateTime createTime;
}
