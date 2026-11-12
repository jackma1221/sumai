package com.hmall.item;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品服务启动类：端口 8081，负责商品 CRUD、ES 检索、多级缓存与销量/库存消息消费。
 */
@MapperScan("com.hmall.item.mapper")
@SpringBootApplication(scanBasePackages = "com.hmall")
public class ItemApplication {
    public static void main(String[] args) {
        SpringApplication.run(ItemApplication.class, args);
    }
}
