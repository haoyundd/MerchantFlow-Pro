# 面试功能补全开发

- [x] 新增 RocketMQ 与 Caffeine 依赖、Docker Compose 服务和运行配置
- [x] 实现 Caffeine + Redis 两级缓存查询与“先更新 MySQL，再删两级缓存”
- [x] 实现 Redis 删除失败后的 RocketMQ 异步补偿删除
- [x] 实现 Redis + Lua 注解限流
- [x] 实现 AK/SK 签名认证
- [x] 实现 SpringTask 超时未支付订单取消与库存回补
- [x] 编译、Docker 启动与核心接口验证

## Review

- `mvn -q -DskipTests compile` 已通过。
- `docker compose up -d --build backend` 已通过，MySQL、Redis、RocketMQ NameServer、RocketMQ Broker、backend、nginx 均正常运行。
- `GET /api/shop-type/list` 返回成功，前端 nginx 到后端代理链路正常。
- `POST /api/user/login` 使用 `admin/123456` 返回 token，`GET /api/user/me` 可返回 admin 用户信息。
- `GET /api/shop/1` 可写入 Redis 缓存，`TTL cache:shop:1` 为 `1800`，说明 TTL 兜底生效。
- 登录接口连续请求超过限制返回 HTTP `429`，注解限流生效。
- 店铺更新接口无 AK/SK 返回 HTTP `401`，正确签名返回成功，重复 nonce 返回 HTTP `401`。
- 店铺更新后 Redis `cache:shop:1` 被删除；开启故障注入后，RocketMQ 消费者最终补偿删除该 key。
- 秒杀接口可快速返回订单 id，异步线程可落库订单，重复下单会被 Lua 拦截。
- 超时未支付订单会被 SpringTask 更新为取消状态，并回补 MySQL 库存、Redis 库存和一人一单集合。
- 验证中修复了两个运行问题：RocketMQ Broker 持久化卷权限导致启动失败；backend 容器时区与 MySQL 不一致导致超时订单扫描不命中。
