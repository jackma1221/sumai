package com.hmall.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 交易服务启动类：端口 8082，负责购物车、订单、库存扣减编排与订单事件发送。
 */
@MapperScan("com.hmall.trade.mapper")
@EnableFeignClients(basePackages = "com.hmall.api.client")
@SpringBootApplication(scanBasePackages = "com.hmall")
public class TradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
