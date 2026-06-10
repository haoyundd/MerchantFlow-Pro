package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private String redisPort;

    @Value("${spring.redis.password}")
    private String redisPassword;

    @Bean
    public RedissonClient redissonClient() {
        // 创建 Redisson 配置对象，统一复用 application.yaml 中的 Redis 配置
        Config config = new Config();
        // 添加单机 Redis 地址，避免 Redisson 与 StringRedisTemplate 使用不同端口
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(redisPassword);
        // 创建 Redisson 客户端，供秒杀、分布式锁等功能使用
        return Redisson.create(config);
    }
}
