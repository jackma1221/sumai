package com.hmall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.cache.ItemBloomFilter;
import com.hmall.cache.ItemCacheService;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.domain.dto.ItemDTO;
import com.hmall.domain.dto.OrderDetailDTO;
import com.hmall.domain.po.Item;
import com.hmall.mapper.ItemMapper;
import com.hmall.search.ItemIndexService;
import com.hmall.service.IItemService;
import com.hmall.utils.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    /** 商品详情缓存重建锁 */
    private static final String DETAIL_LOCK_PREFIX = "lock:item:detail:";
    /** 商品库存扣减锁 */
    private static final String STOCK_LOCK_PREFIX = "lock:item:stock:";
    /** 锁的自动过期时间，防止服务宕机后死锁 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    /** 未拿到重建锁时，等待缓存重建的最长次数（每次 50ms） */
    private static final int WAIT_TIMES = 3;

    private final ItemCacheService cacheService;
    private final ItemBloomFilter bloomFilter;
    private final RedisLock redisLock;
    private final ItemIndexService itemIndexService;

    @Override
    public ItemDTO queryItemById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        // 1. 布隆过滤器：判定一定不存在，直接返回，请求不会打到 MySQL
        if (!bloomFilter.mightContain(id)) {
            log.debug("布隆过滤器拦截不存在的商品，id={}", id);
            return null;
        }
        // 2. 查 Redis 缓存
        ItemCacheService.Hit hit = cacheService.get(id);
        if (hit.isHit()) {
            return hit.getItem();
        }
        // 3. 缓存未命中：加互斥锁，只允许一个线程重建缓存，其余线程等待，避免缓存击穿
        String lockKey = DETAIL_LOCK_PREFIX + id;
        String token = redisLock.tryLock(lockKey, LOCK_TTL);
        if (token == null) {
            return waitForCache(id);
        }
        try {
            // 双重检查：可能其他线程刚重建完缓存
            hit = cacheService.get(id);
            if (hit.isHit()) {
                return hit.getItem();
            }
            return loadAndCache(id);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    /**
     * 没抢到重建锁时的等待逻辑：短暂自旋读缓存，最后兜底查一次库
     */
    private ItemDTO waitForCache(Long id) {
        for (int i = 0; i < WAIT_TIMES; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            ItemCacheService.Hit hit = cacheService.get(id);
            if (hit.isHit()) {
                return hit.getItem();
            }
        }
        Item item = getById(id);
        return item == null ? null : BeanUtils.copyBean(item, ItemDTO.class);
    }

    /**
     * 查库并回填缓存；商品不存在时写入空值，防止缓存穿透
     */
    private ItemDTO loadAndCache(Long id) {
        Item item = getById(id);
        if (item == null) {
            cacheService.putNull(id);
            return null;
        }
        ItemDTO dto = BeanUtils.copyBean(item, ItemDTO.class);
        cacheService.put(dto);
        // 真实存在的商品补写布隆过滤器，避免后续被误挡
        bloomFilter.put(id);
        return dto;
    }

    @Override
    public void deductStock(List<OrderDetailDTO> items) {
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 按 itemId 排序后逐个加锁，多商品下单时不会互相等待造成死锁
        List<OrderDetailDTO> sorted = items.stream()
                .sorted(Comparator.comparing(OrderDetailDTO::getItemId))
                .collect(Collectors.toList());
        for (OrderDetailDTO detail : sorted) {
            Long itemId = detail.getItemId();
            String lockKey = STOCK_LOCK_PREFIX + itemId;
            redisLock.executeWithLock(lockKey, LOCK_TTL, 1000L, () -> {
                // 条件更新：stock >= num 时才会扣减成功，数据库层面兜底防止超卖
                int rows = baseMapper.updateStock(detail);
                if (rows <= 0) {
                    throw new BizIllegalException("商品库存不足，itemId=" + itemId);
                }
                // 库存变化后清掉详情缓存，下次查询回源
                cacheService.evict(itemId);
                return null;
            });
        }
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        if (CollUtils.isEmpty(ids)) {
            return CollUtils.emptyList();
        }
        // 1. 先批量读缓存
        Map<Long, ItemDTO> cached = cacheService.getBatch(ids);
        // 2. 未命中的 id 再查库
        List<Long> missing = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !cached.containsKey(id))
                .distinct()
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            List<Item> items = listByIds(missing);
            if (!items.isEmpty()) {
                List<ItemDTO> dtos = BeanUtils.copyList(items, ItemDTO.class);
                cacheService.putBatch(dtos);
                dtos.forEach(dto -> cached.put(dto.getId(), dto));
            }
        }
        // 3. 按传入顺序返回存在的商品
        return ids.stream()
                .map(cached::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void saveItem(ItemDTO item) {
        Item entity = BeanUtils.copyBean(item, Item.class);
        save(entity);
        // 新增商品同步进 ES，保证检索能立刻搜到
        itemIndexService.upsert(entity);
    }

    @Override
    public void updateItem(ItemDTO item) {
        // 不允许通过该接口修改商品状态
        item.setStatus(null);
        updateById(BeanUtils.copyBean(item, Item.class));
        cacheService.evict(item.getId());
        itemIndexService.syncById(item.getId());
    }

    @Override
    public void updateItemStatus(Long id, Integer status) {
        Item item = new Item().setId(id).setStatus(status);
        updateById(item);
        cacheService.evict(id);
        // 下架/删除的商品需要从索引里移除，避免仍被搜出来
        if (status != null && status != 1) {
            itemIndexService.delete(id);
        } else {
            itemIndexService.syncById(id);
        }
    }

    @Override
    public void deleteItemById(Long id) {
        removeById(id);
        cacheService.evict(id);
        itemIndexService.delete(id);
    }
}
