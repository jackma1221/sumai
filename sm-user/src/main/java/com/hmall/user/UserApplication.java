package com.hmall.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类：端口 8084，负责登录签发 JWT、余额扣减、收货地址。
 */
@MapperScan("com.hmall.user.mapper")
@SpringBootApplication(scanBasePackages = "com.hmall")
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
