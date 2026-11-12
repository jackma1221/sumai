package com.hmall.api.client;

import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * 商品服务远程调用契约。
 * <p>服务名 item-service 与 Nacos 注册名一致，网关/Feign 都通过 lb:// 负载均衡。</p>
 */
@FeignClient(name = "item-service", path = "/items")
public interface ItemClient {

    @GetMapping("/{id}")
    ItemDTO queryItemById(@PathVariable("id") Long id);

    @GetMapping
    List<ItemDTO> queryItemByIds(@RequestParam("ids") Collection<Long> ids);

    @PutMapping("/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);
}
