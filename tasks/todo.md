# 项目结构与功能调研（2026-09-04）

## MerchantFlow 全功能修复与验证（本轮）

- [x] 补齐博客评论接口、权限、分页、点赞和前端交互
- [x] 补齐秒杀订单查询、本地模拟支付、取消和幂等回补
- [x] 完善用户、缓存、博客、关注、聊天和上传功能测试
- [x] 完成 Docker、Redis、MySQL、RocketMQ 故障实验脚本和最终运行验收

## MerchantFlow 全功能修复与验证 Review（2026-09-20）

- 评论接口已支持一级评论、回复、分页、作者删除、点赞切换和前端提交/刷新/错误提示。
- 秒杀订单已支持查询、待支付状态、本地模拟支付、用户取消、超时取消和库存预占幂等回补；真实验证了下单、异步落库、支付、重复支付拒绝和取消回补。
- API 冒烟已覆盖登录、用户信息、签到、验证码关闭提示、店铺/店铺类型/GEO 查询、优惠券、博客、关注、评论、订单、聊天历史和登出后 Token 失效。
- 已修正 Lab 故障脚本：仅在 `LAB_MODE=true` 下允许变更；Redis/MySQL 先切换代理覆盖层并等待后端 healthy，恢复时强制切回基础 Compose。
- 验证结果：Maven 编译和测试通过（5/5），Docker Compose 配置通过，Nginx 在 backend 强制重建后仍能访问 `/api/shop-type/list`，MySQL/Redis/RocketMQ 数据卷未删除。

- [x] 建立项目全景：目录、构建配置、启动入口与依赖
- [x] 梳理后端模块、接口、核心业务流程及基础设施
- [x] 梳理前端页面、路由、API 调用和用户功能
- [x] 检查数据库、缓存、消息队列、部署配置与测试覆盖
- [x] 汇总项目结构、功能清单、关键调用链和当前状态

## Review

- 当前项目为 Spring Boot 单体后端 + 静态 Vue 2/Element UI 前端 + MySQL/Redis/RocketMQ/Docker Compose。
- 已核对用户、商户、优惠券秒杀、探店笔记、关注 Feed、缓存、安全限流、AI SSE、可观测性与故障实验室。
- 未完成/未闭环能力：`UserController.logout()` 返回“功能未完成”；`BlogCommentsController` 无接口；前端无聊天页面；底部“地图/消息”入口没有跳转逻辑；秒杀仅创建未支付订单，没有支付流程。

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

## AIOps 故障实验配合：Redis 延迟

- [x] 为 `lab/scenarios.ps1` 增加 UTF-8 BOM，兼容 Windows PowerShell 5.1，避免中文源码被按 GB2312 误解析。
- [x] 用 Toxiproxy 对 Redis 下游真实注入 1500ms 延迟，并由 k6 访问 MerchantFlow 真实接口产生观测信号。
- [x] 实验结束自动删除 toxic，保留 MySQL、Redis、RocketMQ 数据卷和容器。
- [x] 通过 `node --check`、Compose 配置校验、PowerShell 5.1 AST/实际短场景和 90 秒真实场景回归。

## Review

- 本轮真实场景最终恢复成功，Redis toxic 数为 0，AIOps 侧无活跃 MerchantFlow 高延迟告警。
- k6 真实请求 1314 次，失败率 0%，HTTP P95 约 1.58 秒；AIOps Trace 中可看到 Redis Lettuce 子 Span 约 1.5 秒。
- MySQL 延迟场景也已真实执行并自动恢复：Toxiproxy 注入 1200ms，k6 产生 382 次请求，HTTP P95 约 2.43 秒。
- MySQL 场景未单独生成新 Incident，是因为前一轮高延迟告警指纹仍在 5 分钟窗口内 firing；后续必须等待告警恢复后再验收新的诊断结果。
- 等待告警窗口恢复后重新执行 MySQL 延迟，AIOps 已生成 `DIAGNOSED / MYSQL_OUTAGE / 65%`，Trace 中包含 MySQL 慢子 Span，场景结束后代理自动恢复。

## RocketMQ 中断实验

- [x] 新增 `lab/prepare-rocketmq.ps1`，按 MySQL 真实库存准备 Redis 秒杀缓存，并保护已落库订单。
- [x] 增强 `lab/k6/rocketmq-probe.js`，区分 Redis 前置失败和 RocketMQ Broker 发送失败。
- [x] 停止真实 RocketMQ Broker，使用两个优惠券请求验证 Redis Lua 放行后返回 HTTP 500。
- [x] 观察到真实 `RocketMQTemplate.syncSend failed` / `No route info of this topic` 日志，场景结束自动恢复 Broker。
- [x] AIOps 自动生成 `ROCKETMQ_FAILURE` 诊断并持久化待审核 Runbook Draft。
- [x] 记录实验中的用户限流边界和重复告警窗口问题。

# MerchantFlow 数据一致性与全功能运行改造（2026-09-19）

- [x] 第一批：Redis 缓存预热、GEO 重建、店铺类型 TTL、秒杀有效期校验与 Lua 边界
- [x] 第二批：秒杀 MQ 失败补偿、库存对账、订单幂等和演示数据脚本
- [x] 第三批：Nginx 动态服务发现、Redis 测试隔离和测试用例补齐
- [x] 第四批：启动两套 Compose，执行接口、缓存、消息和故障演练验收

## Review

- 已增加 Redis 启动/定时校准、GEO 预热、秒杀有效期校验、MQ 失败回滚、订单唯一约束和可重复演示券脚本。
- 已验证 backend 重建后 Nginx API 仍返回 200，旧数据卷未删除。
- `mvn -DskipTests package` 和 `mvn test` 通过，当前集成测试 5 个全部通过。
- RocketMQ 中断真实实验已验证：请求失败、Redis 预占回滚、MySQL 无孤立订单、Broker 自动恢复。
- 当前仍明确保留：评论接口、支付流程、前端聊天页面等原有业务缺口。
## MerchantFlow 全功能修复与验证（本轮）

- [ ] 补齐博客评论接口、权限、分页、点赞和前端交互
- [ ] 补齐秒杀订单查询、本地模拟支付、取消和幂等回补
- [ ] 完善用户、缓存、博客、关注、聊天和上传功能测试
- [ ] 完成 Docker、Redis、MySQL、RocketMQ 故障实验与最终验收
