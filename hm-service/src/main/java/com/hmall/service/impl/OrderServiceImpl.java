package com.hmall.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;
import com.hmall.domain.dto.ItemDTO;
import com.hmall.domain.dto.OrderDetailDTO;
import com.hmall.domain.dto.OrderFormDTO;
import com.hmall.domain.po.Order;
import com.hmall.domain.po.OrderDetail;
import com.hmall.mapper.OrderMapper;
import com.hmall.mq.OrderEventProducer;
import com.hmall.mq.OrderMessage;
import com.hmall.service.ICartService;
import com.hmall.service.IItemService;
import com.hmall.service.IOrderDetailService;
import com.hmall.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 订单服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    /** 下单防重 key 前缀 */
    private static final String SUBMIT_KEY_PREFIX = "order:submit:";
    /** 防重有效期：10 秒内相同订单不允许重复提交 */
    private static final Duration SUBMIT_TTL = Duration.ofSeconds(10);

    private final IItemService itemService;
    private final IOrderDetailService detailService;
    private final ICartService cartService;
    private final StringRedisTemplate redisTemplate;
    private final OrderEventProducer orderEventProducer;

    @Override
    @Transactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        if (CollUtils.isEmpty(detailDTOS)) {
            throw new BadRequestException("订单商品不能为空");
        }
        Long userId = UserContext.getUser();

        // 1. 接口防重提交：同一用户 + 同一批商品 + 同一地址，10 秒内只允许提交一次
        String submitKey = SUBMIT_KEY_PREFIX + userId + ":" + DigestUtil.md5Hex(submitSignature(orderFormDTO));
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(submitKey, "1", SUBMIT_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizIllegalException("订单正在处理中，请勿重复提交");
        }

        try {
            Long orderId = doCreateOrder(orderFormDTO, detailDTOS, userId);
            // 6. 事务提交后再发 MQ，避免消息先到、事务回滚造成"库存没扣但销量加了"
            sendOrderMessageAfterCommit(buildMessage(orderId, detailDTOS, userId));
            return orderId;
        } catch (RuntimeException e) {
            // 下单失败时释放防重标记，允许用户调整后重试
            redisTemplate.delete(submitKey);
            throw e;
        }
    }

    private Long doCreateOrder(OrderFormDTO orderFormDTO, List<OrderDetailDTO> detailDTOS, Long userId) {
        // 2. 订单数据
        Order order = new Order();
        // 2.1.获取商品id和数量的Map（同一商品出现多次时数量累加）
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum, Integer::sum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 2.2.查询商品
        List<ItemDTO> items = itemService.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 2.3.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 2.4.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(userId);
        order.setStatus(1);
        // 2.5.将Order写入数据库order表中
        save(order);

        // 3. 保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 4. 扣减库存（先扣库存再清购物车：库存不足时事务回滚，购物车不会被误清）
        itemService.deductStock(detailDTOS);

        // 5. 清理购物车中已下单的商品
        cartService.removeByItemIds(itemIds);

        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    /**
     * 把下单明细合并成消息体（同一商品出现多次时数量累加）
     */
    private OrderMessage buildMessage(Long orderId, List<OrderDetailDTO> detailDTOS, Long userId) {
        Map<Long, Integer> numMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum, Integer::sum));
        List<OrderMessage.ItemNum> items = numMap.entrySet().stream()
                .map(entry -> new OrderMessage.ItemNum(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        OrderMessage message = new OrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setCreateTime(System.currentTimeMillis());
        message.setItems(items);
        return message;
    }

    /**
     * 事务提交后再发送 MQ 消息；没有事务时直接发送。
     * 发送失败只记日志，不回滚已成功的订单。
     */
    private void sendOrderMessageAfterCommit(OrderMessage message) {
        Runnable send = () -> {
            try {
                orderEventProducer.sendOrderCreated(message);
            } catch (Exception e) {
                log.warn("[MQ] 下单消息发送失败，orderId={}，原因={}", message.getOrderId(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /**
     * 下单签名：地址 + 支付方式 + 商品明细（按 itemId 排序，保证同一订单参数生成同一个 md5）
     */
    private String submitSignature(OrderFormDTO orderFormDTO) {
        StringBuilder sb = new StringBuilder();
        sb.append(orderFormDTO.getAddressId()).append('|')
                .append(orderFormDTO.getPaymentType()).append('|');
        orderFormDTO.getDetails().stream()
                .sorted(Comparator.comparing(OrderDetailDTO::getItemId))
                .forEach(detail -> sb.append(detail.getItemId()).append(':').append(detail.getNum()).append(','));
        return sb.toString();
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
