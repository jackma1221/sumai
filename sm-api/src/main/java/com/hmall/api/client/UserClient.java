package com.hmall.api.client;

import com.hmall.api.dto.AddressDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户服务远程调用契约：收货地址、余额扣减。
 */
@FeignClient(name = "user-service")
public interface UserClient {

    @GetMapping("/addresses/{id}")
    AddressDTO findAddressById(@PathVariable("id") Long id);

    @PutMapping("/users/money/deduct")
    void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount);
}
