# Docker 部署与账号密码登录改造

- [x] 创建并切换功能分支
- [x] 新增 Docker Compose、后端 Dockerfile、Docker nginx 配置
- [x] 生成本地数据库备份与 Docker 初始化 SQL
- [x] 改造后端配置为环境变量驱动
- [x] 改造登录为 admin/123456 账号密码登录
- [x] 执行本地与 Docker 验证
- [x] 提交并推送功能分支

## Review

- 本地编译通过。
- Docker Compose 从空卷启动通过。
- `GET /api/shop-type/list` 正常返回数据。
- `POST /api/user/login` 使用 `admin/123456` 返回 token。
- `GET /api/user/me` 可拿到 admin 用户信息。
- MySQL 容器内已存在 `tb_user.phone='admin'`。
- Redis Stream 消费组 `stream.orders/g1` 已初始化，启动日志未再出现 `NOGROUP`。
