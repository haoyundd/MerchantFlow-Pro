package com.hmdp.mq;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.CacheDeleteMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${hmdp.cache.delete-topic}",
        consumerGroup = "hmdp-cache-delete-consumer",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class CacheDeleteCompensationConsumer implements RocketMQListener<CacheDeleteMessage> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, Object> localCache;

    @Override
    public void onMessage(CacheDeleteMessage message) {
        // 补偿消费者只做删除动作，不重建缓存，确保 MySQL 是唯一真实数据源。
        localCache.invalidate(message.getCacheKey());
        stringRedisTemplate.delete(message.getCacheKey());
        log.info("缓存删除补偿完成，bizType={}, bizId={}, cacheKey={}",
                message.getBizType(), message.getBizId(), message.getCacheKey());
    }
}
