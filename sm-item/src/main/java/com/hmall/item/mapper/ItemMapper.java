package com.hmall.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 扣减库存：带 stock >= num 条件，更新行数为 0 说明库存不足，从数据库层面防止超卖
     */
    @Update("UPDATE item SET stock = stock - #{num} WHERE id = #{itemId} AND stock >= #{num}")
    int updateStock(OrderDetailDTO orderDetail);

    /**
     * 增加销量：由下单成功消息的消费端异步执行，不占用下单主链路
     */
    @Update("UPDATE item SET sold = sold + #{num} WHERE id = #{itemId}")
    int increaseSold(@Param("itemId") Long itemId, @Param("num") Integer num);

    /**
     * 回滚库存：订单超时未支付被取消时，把扣掉的库存加回来
     */
    @Update("UPDATE item SET stock = stock + #{num} WHERE id = #{itemId}")
    int restoreStock(@Param("itemId") Long itemId, @Param("num") Integer num);
}
