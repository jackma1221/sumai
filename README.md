# sumai（速卖商城 · 微服务版）

基于速卖商城改造的电商后端工程，在单体版的基础上做了 **服务拆分 + 注册/配置中心 + 网关统一鉴权**，并把简历里的 6 条技术点全部落到可运行、可验证的代码上。

## 一、技术点与实现对应

| 简历技术点 | 工程实现 | 验证方式 |
|---|---|---|
| 12 张核心表 + 20+ 商品接口 | `item/order/order_detail/cart/address/user/order_logistics/pay_order` + 本次新增 `item_stock_log / order_status_log / pay_log / search_hot_word`，共 12 张 | `show tables` 12 行；下单/支付/关单后新表有流水 |
| 6 模块拆分 + Nacos + Gateway + JWT | `sm-api / sm-item / sm-trade / sm-pay / sm-user / sm-gateway`；Nacos 2.2.3 做注册 + 配置中心（`hm-shared.yaml`）；Gateway 全局路由 + `AuthGlobalFilter` 统一校验 JWT 并向下游注入 `X-User-Id` | Nacos 服务列表 5 个实例；无 token 访问 `/carts` 返回 401 |
| ES 全文检索 | 索引 `item` 88,476 条，关键字检索 + 品牌/类目/价格过滤 + 价格排序 + 名称高亮 + 品牌聚合，ES 异常自动降级 MySQL | `/search/items?key=手机` 返回真实聚合并高亮；p50 15.11ms（MySQL LIKE 同条件约 55ms/次） |
| Redis 多级缓存 + 布隆 + 互斥锁 | 布隆过滤器（`item:bloom`）→ Redis 详情缓存（随机 TTL）→ 互斥锁重建（`lock:item:detail:*`）+ 空值哨兵；库存扣减带 `lock:item:stock:*` 分布式锁 | 网关压测 2198 QPS / p99 34.66ms（详见 `docs/perf-report.md`） |
| 购物车 + 下单全流程 + 防重 + 防超卖 + MQ | 购物车 Redis Hash；下单幂等键 + 商品排序加锁 + `stock >= num` 条件更新；RabbitMQ `order.create`（加销量）/`order.delay`（TTL 60s 死信）→ `order.cancel`（关单）→ `order.cancelled`（回滚库存） | 下单 3s 内 stock-1、60s 后订单 1→5 且库存回滚，`item_stock_log` 出现 deduct/restore 一对流水 |
| Feign + Sentinel + Docker | `sm-api` 四组 Feign 客户端（Item/User/Trade/Pay），`FeignConfig` 透传 `X-User-Id`/`authorization`；Sentinel 流控（itemDetail 30 QPS、createOrder 10 QPS）+ 熔断（RT/异常比例）；通用 Dockerfile + compose 一键起 5 服务 + Nacos + 中间件 | `/items/slow` 连续慢调用后返回 429「已触发熔断降级」；压测失败率 0/32992 |

## 二、模块结构

```
sumai
├── sm-common         公共能力：统一响应/异常/分页、JWT 工具、Redis 分布式锁、UserContext + 网关用户拦截器
├── sm-api            Feign 契约层：ItemClient / UserClient / TradeClient / PayClient、共享 DTO、OrderMessage、FeignConfig
├── sm-item           商品服务(8081)：商品 CRUD、库存扣减/回滚、ES 检索、缓存、库存流水、订单消息消费、Sentinel
├── sm-trade          交易服务(8082)：购物车、下单、订单状态、延迟取消、Feign 调用商品/用户
├── sm-pay            支付服务(8083)：支付单、余额支付（Feign 扣减用户余额 + 回写订单状态）、支付流水
├── sm-user           用户服务(8084)：登录签发 JWT、地址管理、余额扣减
├── sm-gateway        网关(8080)：路由转发、全局 CORS、JWT 统一鉴权
├── docker/mysql-init 12 张表建表脚本
├── scripts           本地一键启动脚本
├── docs              压测报告等文档
├── Dockerfile        通用多阶段构建（ARG MODULE 指定模块）
└── docker-compose.yml  MySQL/Redis/ES/RabbitMQ/Nacos/Sentinel + 5 服务
```

端口约定：gateway 8080、item 8081、trade 8082、pay 8083、user 8084、Nacos 8848、Sentinel Dashboard 8858。

## 三、本地启动（Windows）

前置：JDK 11、Maven 3.8+、MySQL 8、Redis、Elasticsearch 7.17、RabbitMQ 3.12、Nacos 2.2.3。

```bash
# 1. 建库并建表（12 张表）
mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS hmall"
mysql -uroot -proot hmall < docker/mysql-init/01-schema.sql
# 商品数据（88,476 条）按需从速卖商城 hmall.sql 导入 item 表

# 2. 起 Nacos（standalone）并发布共享配置
#    Windows: bin\startup.cmd -m standalone ；控制台 http://127.0.0.1:8848/nacos
#    在「配置管理」新建 dataId=hm-shared.yaml, group=DEFAULT_GROUP，内容见 docs/nacos-hm-shared.yaml

# 3. 打包并启动 5 个服务 + 网关
mvn -DskipTests package
powershell -File scripts/start-all.ps1
```

启动后在 Nacos 控制台「服务管理」应能看到 `gateway / item-service / trade-service / pay-service / user-service` 五个实例。

## 四、关键接口（统一走 8080 网关）

| 接口 | 说明 | 鉴权 |
|---|---|---|
| `POST /users/login` | 登录，返回 JWT | 白名单 |
| `GET /items/{id}` | 商品详情（布隆 + 缓存 + 互斥锁） | 白名单 |
| `GET /items/slow?ms=500` | 慢调用演示，用于观察 RT 熔断降级 | 白名单 |
| `GET /search/items` | ES 检索（关键字/过滤/排序/高亮/聚合） | 白名单 |
| `GET /search/list` | 兼容门户分页结构，同时累加搜索热词 | 白名单 |
| `GET /carts`、`POST /carts` | 购物车查询/加购 | 需 `authorization: Bearer <token>` |
| `POST /orders` | 下单（幂等、地址校验、扣库存、发消息） | 需登录 |
| `POST /pay-orders`、`POST /pay-orders/{id}` | 申请支付单、余额支付 | 需登录 |

网关白名单：`/users/login`、`/search/**`、`/items/**`、`/hi`、`/error`；其余路径必须在请求头带 `authorization`，校验失败返回 `{"code":401,"msg":"请先登录"}`。

## 五、验证与压测结论

- 端到端链路：登录 → 商品详情 → 搜索 → 加购 → 下单 → 支付 → （60s 未支付）自动关单 + 库存回滚，全部实测通过，数据落在 `order_status_log`、`item_stock_log`、`pay_log`、`search_hot_word` 中。
- 商品详情（经网关）：并发 32 持续 15s，**2198.5 QPS，失败 0，p99 34.66ms**；直连 item 服务 3327.8 QPS，说明网关带来的损耗约 34%。
- 熔断降级：`GET /items/slow?ms=500` 并发 40 次后，后续请求稳定返回 `429 商品服务繁忙，已触发熔断降级，请稍后重试`，10s 后恢复。
- 详细数据、脚本与复现命令见 [`docs/perf-report.md`](docs/perf-report.md)。

> 说明：本机没有 Docker 环境，`docker-compose.yml` 与 `Dockerfile` 是按镜像构建规范编写的，未在本机实跑；本地验证全部采用真实进程启动。
