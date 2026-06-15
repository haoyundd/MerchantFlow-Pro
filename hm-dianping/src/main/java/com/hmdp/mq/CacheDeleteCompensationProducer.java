package com.hmdp.mq;

import com.hmdp.dto.CacheDeleteMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class CacheDeleteCompensationProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Value("${hmdp.cache.delete-topic}")
    private String cacheDeleteTopic;

    public void send(String bizType, String bizId, String cacheKey, String reason) {
        // 只在缓存删除失败时发送补偿消息，RocketMQ 不负责刷新缓存数据。
        CacheDeleteMessage message = new CacheDeleteMessage(bizType, bizId, cacheKey, reason);
        try {
            rocketMQTemplate.convertAndSend(cacheDeleteTopic, message);
            log.warn("缓存删除失败，已发送 RocketMQ 补偿消息：{}", message);
        } catch (Exception e) {
            // 这里不能回滚数据库，只记录日志；Redis TTL 仍然是最终兜底。
            log.error("发送缓存删除补偿消息失败，cacheKey={}", cacheKey, e);
        }
    }
}
