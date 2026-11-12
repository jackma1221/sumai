package com.hmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.trade.domain.dto.CartFormDTO;
import com.hmall.trade.domain.po.Cart;
import com.hmall.trade.domain.vo.CartVO;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 购物车服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface ICartService extends IService<Cart> {

    void addItem2Cart(CartFormDTO cartFormDTO);

    List<CartVO> queryMyCarts();

    void removeByItemIds(Collection<Long> itemIds);

    /**
     * 删除购物车中的某个商品（itemId）
     */
    void removeByItemId(Long itemId);

    /**
     * 更新购物车条目（数量）
     */
    void updateCart(Cart cart);
}
