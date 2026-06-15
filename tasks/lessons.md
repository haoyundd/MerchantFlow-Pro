# Lessons

- 二级缓存一致性不要理解成“MQ 主动刷新缓存”。本项目采用“先更新 MySQL，再删除 Caffeine 和 Redis；Redis 删除失败才发送 RocketMQ 补偿删除消息；Redis TTL 作为最终兜底”的策略。
