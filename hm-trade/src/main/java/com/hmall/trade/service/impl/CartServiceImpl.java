package com.hmall.trade.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.domain.dto.CartFormDTO;
import com.hmall.api.dto.ItemDTO;
import com.hmall.trade.domain.po.Cart;
import com.hmall.trade.domain.vo.CartVO;
import com.hmall.trade.mapper.CartMapper;
import com.hmall.trade.remote.ItemRemoteService;
import com.hmall.trade.service.ICartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 购物车服务实现类：基于 Redis Hash 存储
 * </p>
 * key：cart:{userId}，field：itemId，value：Cart 的 JSON
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    /** 购物车 key 前缀 */
    private static final String CART_KEY_PREFIX = "cart:";
    /** 购物车最多放 10 种商品 */
    private static final int MAX_ITEM_TYPES = 10;
    /** 购物车数据保留 30 天 */
    private static final Duration CART_TTL = Duration.ofDays(30);

    private final ItemRemoteService itemRemoteService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        Long userId = UserContext.getUser();
        Long itemId = cartFormDTO.getItemId();
        if (itemId == null) {
            throw new BizIllegalException("商品id不能为空");
        }
        String key = cartKey(userId);
        String field = itemId.toString();

        // 1. 已存在则数量 +1（value 存的是 Cart 的 JSON，需要先反序列化再累加，不能直接用 HINCRBY）
        Object exist = redisTemplate.opsForHash().get(key, field);
        if (exist != null) {
            Cart stored = parse(exist.toString());
            if (stored != null) {
                int num = stored.getNum() == null ? 0 : stored.getNum();
                stored.setNum(num + 1);
                stored.setUpdateTime(LocalDateTime.now());
                redisTemplate.opsForHash().put(key, field, toJson(stored));
                return;
            }
        }

        // 2. 不存在则校验商品是否可购买
        ItemDTO item = itemRemoteService.queryItemById(itemId);
        if (item == null) {
            throw new BizIllegalException("商品不存在");
        }
        if (item.getStatus() == null || item.getStatus() != 1) {
            throw new BizIllegalException("商品已下架");
        }

        // 3. 校验购物车容量
        Long size = redisTemplate.opsForHash().size(key);
        if (size != null && size >= MAX_ITEM_TYPES) {
            throw new BizIllegalException(StrUtil.format("购物车最多只能放{}种商品", MAX_ITEM_TYPES));
        }

        // 4. 写入购物车（价格、标题以数据库最新数据为准，避免前端传值被篡改）
        Cart cart = new Cart()
                .setId(itemId)
                .setUserId(userId)
                .setItemId(itemId)
                .setNum(1)
                .setName(item.getName())
                .setSpec(item.getSpec())
                .setPrice(item.getPrice())
                .setImage(item.getImage())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        redisTemplate.opsForHash().put(key, field, toJson(cart));
        redisTemplate.expire(key, CART_TTL);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        Long userId = UserContext.getUser();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(cartKey(userId));
        if (entries.isEmpty()) {
            return CollUtils.emptyList();
        }
        // 1. 反序列化购物车条目
        List<Cart> carts = new ArrayList<>(entries.size());
        for (Object value : entries.values()) {
            Cart cart = parse(value.toString());
            if (cart != null) {
                carts.add(cart);
            }
        }
        if (carts.isEmpty()) {
            return CollUtils.emptyList();
        }
        carts.sort(Comparator.comparing(Cart::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));

        // 2. 转换 VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        // 3. 补充商品最新价格、状态、库存
        handleCartItems(vos);

        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        List<ItemDTO> items = itemRemoteService.queryItemByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        Map<Long, ItemDTO> itemMap = items.stream()
                .collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        if (CollUtils.isEmpty(itemIds)) {
            return;
        }
        Long userId = UserContext.getUser();
        Object[] fields = itemIds.stream().map(String::valueOf).toArray();
        redisTemplate.opsForHash().delete(cartKey(userId), fields);
    }

    @Override
    public void removeByItemId(Long itemId) {
        if (itemId == null) {
            return;
        }
        redisTemplate.opsForHash().delete(cartKey(UserContext.getUser()), itemId.toString());
    }

    @Override
    public void updateCart(Cart cart) {
        Long userId = UserContext.getUser();
        // 兼容前端只传 id（此时 id 就是 itemId）的情况
        Long itemId = cart.getItemId() != null ? cart.getItemId() : cart.getId();
        if (itemId == null) {
            return;
        }
        String key = cartKey(userId);
        String field = itemId.toString();
        Object exist = redisTemplate.opsForHash().get(key, field);
        if (exist == null) {
            return;
        }
        Cart stored = parse(exist.toString());
        if (stored == null) {
            return;
        }
        if (cart.getNum() != null) {
            if (cart.getNum() <= 0) {
                throw new BizIllegalException("商品数量必须大于0");
            }
            stored.setNum(cart.getNum());
        }
        stored.setUpdateTime(LocalDateTime.now());
        redisTemplate.opsForHash().put(key, field, toJson(stored));
    }

    private String cartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }

    private String toJson(Cart cart) {
        try {
            return objectMapper.writeValueAsString(cart);
        } catch (Exception e) {
            throw new BizIllegalException("购物车数据序列化失败");
        }
    }

    private Cart parse(String json) {
        try {
            return objectMapper.readValue(json, Cart.class);
        } catch (Exception e) {
            log.warn("购物车数据反序列化失败，json={}", json, e);
            return null;
        }
    }
}
