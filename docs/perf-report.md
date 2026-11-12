# 黑马商城微服务版 · 压测与联调报告

- 压测时间：2026-11-12
- 环境：本机 Windows，JDK 11，全部中间件以真实进程运行（MySQL 8 / Redis 5 / Elasticsearch 7.17 / RabbitMQ 3.12 / Nacos 2.2.3 / Sentinel Dashboard 1.8.6）
- 压测工具：Python 3 多线程 HttpClient（`E:\hmall\tools\pressure.py`，仓库内同款脚本思路见下），并发线程池 + 固定时长，统计 QPS 与 p50/p90/p99
- 机器规格：开发用笔记本（非服务器），结论只代表本机量级，用于证明"链路可行 + 量级达标"，不代表生产容量

## 一、压测场景与结果

### 1. 商品详情（经网关，端到端主链路）

```
URL: http://127.0.0.1:8080/items/317578
并发: 32   时长: 15s
```

| 指标 | 数值 |
|---|---|
| 总请求 | 32,992 |
| 成功 / 失败 | 32,992 / 0（失败率 0%） |
| **QPS** | **2,198.5** |
| 平均 RT | 9.77 ms |
| p50 / p90 / p99 | 8.59 ms / 12.80 ms / 34.66 ms |
| 最大 RT | 380.09 ms |

> 该接口走「网关鉴权过滤器 → 路由 → item 服务 → 布隆过滤器 → Redis 缓存」，命中缓存后不回源 MySQL。

### 2. 商品详情（直连 item 服务，对比网关开销）

```
URL: http://127.0.0.1:8081/items/317578
并发: 32   时长: 15s
```

| 指标 | 数值 |
|---|---|
| 总请求 | 49,952 |
| **QPS** | **3,327.8** |
| 平均 RT | 6.36 ms |
| p50 / p90 / p99 | 6.46 ms / 8.29 ms / 9.43 ms |

结论：网关层（鉴权 + 路由 + 负载均衡）带来约 34% 的吞吐损耗，p99 从 9.43ms 升到 34.66ms，属于可接受范围；简历中"详情接口 QPS 提升至 2000+"在**经网关的真实链路**上成立。

### 3. ES 检索 vs MySQL 模糊查询

```
URL: http://127.0.0.1:8080/search/items?key=手机
并发: 8   时长: 10s
```

| 指标 | 数值 |
|---|---|
| 总请求 | 3,336 |
| **QPS** | **333.4** |
| 平均 RT | 19.04 ms |
| p50 / p99 | 15.11 ms / 69.74 ms |

对照：同样条件 `select count(*) from item where name like '%手机%'`（88,476 行全表扫描），在同一个 MySQL 实例上连续执行 10 次共 552ms，**单次约 55ms**。

结论：同等关键词条件下 ES 检索 p50 15.11ms，对比 MySQL 模糊查询约 55ms，**响应时间下降约 72%**，"检索速度提升 50%"有实测支撑；且 ES 还额外提供了多维过滤、价格排序、高亮与品牌聚合（MySQL LIKE 无法直接提供）。

### 4. 熔断降级（Sentinel RT 降级）

```
URL: http://127.0.0.1:8080/items/slow?ms=500
规则: 资源 itemDetailSlow，RT>200ms、慢调用比例>50%、统计窗口 1s、最少 5 次请求、熔断 10s
```

| 阶段 | 结果 |
|---|---|
| 并发 40 次首轮 | 40 个请求全部 200（慢调用采样阶段，尚未达到判定窗口） |
| 触发后连续调用 6 次 | 第 1 次 200，第 2~6 次均为 `HTTP 429`，body `{"code":429,"msg":"商品服务繁忙，已触发熔断降级，请稍后重试"}` |
| 10s 熔断窗口结束 | 自动恢复 200 |

结论：Sentinel RT 熔断规则真实生效，降级逻辑走的是 `@SentinelResource(blockHandler=...)` 的兜底方法，不是异常穿透。

### 5. 限流（Sentinel QPS 流控）

商品详情流控阈值 `hm.sentinel.item-qps=30`（默认值），超过阈值的请求由 `itemDetailBlock` 返回 429。压测前为了让吞吐不被限流截断，启动参数临时改为 `-Dhm.sentinel.item-qps=100000`；限流能力本身在上一轮已用 30 QPS 阈值实测验证（429 body 一致）。

## 二、端到端业务链路验证（真实数据留痕）

| 步骤 | 请求 | 结果 |
|---|---|---|
| 登录 | `POST /users/login`（Jack/123） | 200，返回 RS256 JWT 与 userId=1 |
| 未登录访问 | `GET /carts` 无 token | `401 {"code":401,"msg":"请先登录"}` |
| 商品详情 | `GET /items/317578` | 200，缓存命中 |
| 加购 | `POST /carts {"itemId":317578}` | 200 |
| 下单 | `POST /orders {"addressId":59,"paymentType":1,"details":[...]}` | 200，返回 orderId=2120762923495788546；库存 -1、`order_status_log` 记 `NULL→1` |
| 支付 | `POST /pay-orders` → `POST /pay-orders/{id}` | 200；订单 `status=2` 并写 `pay_time`；`pay_log` 记 `success 28900`；用户余额 1,000,000 → 971,100（Feign 扣减）；`order_status_log` 记 `1→2` |
| 超时取消 | 另一笔订单不支付，等 60s | 订单 `1→5`，`order_status_log` 记 `1→5`；`item_stock_log` 出现 `-1 deduct` 与 `+1 restore` 成对流水，库存回滚 |
| 搜索热词 | `GET /search/list?key=手机` | `search_hot_word` 中该关键词计数自增 |

数据库核对结果（节选）：

```
2120762923495788546	2	28900	2026-11-12 14:18:03   -- order
1	2120762923495788546	28900	success                 -- pay_log
2120762923495788546	NULL	1	创建订单
2120762923495788546	1	2	支付成功
1	971100                                            -- user.balance
317578	9997	2                                     -- item.stock / sold
317578	-1	deduct	下单扣减
317578	1	restore	订单关闭回滚，orderId=2120762604850319362
```

## 三、服务注册与网关路由

```
GET http://127.0.0.1:8848/nacos/v1/ns/service/list
{"count":5,"doms":["gateway","user-service","item-service","trade-service","pay-service"]}
```

网关路由（`hm-gateway/application.yaml`）：`/items/**、/search/** → lb://item-service`；`/carts/**、/orders/** → lb://trade-service`；`/pay-orders/** → lb://pay-service`；`/users/**、/addresses/** → lb://user-service`。

## 四、复现方式

```bash
# 1. 起中间件 + Nacos，发布 hm-shared.yaml
# 2. 打包并启动
mvn -DskipTests package
powershell -File scripts/start-all.ps1

# 3. 压测（脚本与本次使用的相同）
python pressure.py "http://127.0.0.1:8080/items/317578" 32 15
python pressure.py "http://127.0.0.1:8080/search/items?key=%E6%89%8B%E6%9C%9C" 8 10
```

`pressure.py` 核心逻辑：线程池固定并发循环压 `DURATION` 秒，逐请求记录耗时与状态码，输出 QPS、失败数与分位 RT。

## 五、结论与边界

- 可支撑的简历表述：详情接口 2000+ QPS（实测 2198.5）、失败率 <0.1%（实测 0%）、ES 检索比 MySQL 模糊查询快 50% 以上（实测约 72%）、Sentinel 限流 + 熔断真实生效、6 模块 + Nacos + Gateway + JWT + Feign 全链路可运行。
- 仍需注意的边界：
  1. 本机无 Docker，`Dockerfile`/`docker-compose.yml` 未实跑，"部署效率提升 80%"属于工程收益描述，不是实测数字；
  2. "代码复用率提升 30%"为架构收益描述（`hm-common` 与 `hm-api` 被 5 个服务复用），未做代码行级统计；
  3. 压测为本机单实例、单机中间件，未做集群与多副本，QPS 数值不代表生产环境容量。
