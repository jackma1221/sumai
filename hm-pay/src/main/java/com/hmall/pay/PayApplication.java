package com.hmall.pay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 支付服务启动类：端口 8083，负责支付单生成、余额支付与支付流水。
 */
@MapperScan("com.hmall.pay.mapper")
@EnableFeignClients(basePackages = "com.hmall.api.client")
@SpringBootApplication(scanBasePackages = "com.hmall")
public class PayApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}
