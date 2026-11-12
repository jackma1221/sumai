package com.hmall.api.client;

import com.hmall.api.dto.PayApplyDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 支付服务远程调用契约：交易服务下单成功后生成支付单。
 */
@FeignClient(name = "pay-service")
public interface PayClient {

    @PostMapping("/pay-orders")
    String applyPayOrder(@RequestBody PayApplyDTO applyDTO);
}
