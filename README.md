# sumai（黑马商城）

基于黑马商城单体版（Spring Boot 2.7 + MyBatis-Plus）改造的电商后端工程，覆盖简历中的几条核心技术点：

| 能力 | 实现 |
|---|---|
| 商品详情多级读链路 | 布隆过滤器防穿透 + Redis 缓存（随机 TTL 防雪崩）+ 互斥锁重建防击穿 + 空值哨兵 |
| 购物车 | Redis Hash（`cart:{userId}`，field=itemId，value=Cart JSON），30 天过期，最多 10 种商品 |
| 下单 | 幂等键防重复提交 + 商品排序加锁 + `stock >= num` 条件更新防超卖 + 事务回滚 |
| 搜索 | Elasticsearch 7.17：全文检索、品牌/类目/价格多维过滤、价格/销量排序、名称高亮、品牌聚合，ES 故障自动降级 MySQL |
| 异步与延迟 | RabbitMQ：下单成功异步加销量；TTL + 死信队列实现超时未支付自动取消并回滚库存 |
| 限流降级 | Sentinel 1.8.6 + Dashboard：商品详情、下单资源 QPS 限流，触发返回 429 |
| 容器化 | 多阶段 Dockerfile + docker-compose（MySQL/Redis/ES/RabbitMQ/Sentinel/应用一键起） |

## 模块结构

```
sumai
├── hm-common        公共模块：统一响应、异常、分页、用户上下文
├── hm-service       业务模块：controller / service / mapper / cache / search / mq / sentinel
├── docker           容器初始化脚本（建表 SQL）
├── Dockerfile
└── docker-compose.yml
```

## 本地启动（Windows）

前置：JDK 11、Maven 3.8+、MySQL 8、Redis、Elasticsearch 7.17、RabbitMQ 3.12（可选：Sentinel Dashboard）。

```bash
# 1. 建库并导入数据（hmall.sql 为黑马商城初始化脚本）
mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS hmall"
mysql -uroot -proot hmall < hmall.sql

# 2. 编译打包
mvn -DskipTests package

# 3. 启动（local profile 指向本机中间件）
java -jar hm-service/target/hm-service.jar --spring.profiles.active=local
```

启动后：
- 接口文档 http://localhost:8080/doc.html
- ES 索引会在启动后异步全量同步（8 万+ 商品，首次约 1~2 分钟，日志可见进度）

## 容器化启动

```bash
docker compose up -d --build
```

首次启动时 MySQL 会自动执行 `docker/mysql-init/01-schema.sql` 建表；如需商品数据，把 `hmall.sql` 放进 `docker/mysql-init/` 后重建容器即可。

## 关键接口

| 接口 | 说明 |
|---|---|
| `GET /items/{id}` | 商品详情（布隆 + 缓存 + 互斥锁），被 Sentinel 保护 |
| `GET /search/items?key=手机&brand=华为&minPrice=100&maxPrice=5000&sortBy=price&isAsc=false&pageNo=1&pageSize=10` | ES 检索，返回高亮与品牌/类目聚合 |
| `POST /search/rebuild` | 手动全量重建索引 |
| `POST /carts` `GET /carts` `PUT /carts` `DELETE /carts/{itemId}` | 购物车（Redis） |
| `POST /orders` | 下单（防重 + 扣库存 + 发 MQ） |
| `PUT /orders/{orderId}` | 标记已支付，延迟消息到期后发现已支付则不再取消 |

## 设计说明

- **防重提交**：`order:submit:{userId}:{md5(地址+支付方式+商品明细)}`，`SETNX` 10 秒；失败删除标记允许重试。
- **防超卖**：先按 itemId 排序避免死锁，锁内执行 `UPDATE item SET stock = stock - n WHERE id = ? AND stock >= n`，影响行数为 0 抛库存不足，事务回滚。
- **缓存一致性**：所有写操作（改价/改状态/删商品/扣库存/取消订单）都清 `item:detail:{id}` 并同步 ES。
- **延迟取消**：消息先进 `order.delay.queue`（无消费者，队列级 TTL），到期死信转发到 `order.cancel.queue`，消费端校验订单状态保证幂等。
- **限流降级**：`@SentinelResource` 埋点 `itemDetail` / `createOrder`，规则启动时用代码加载，Dashboard 可实时改。
