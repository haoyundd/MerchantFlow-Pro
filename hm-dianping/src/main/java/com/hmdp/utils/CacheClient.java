package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.mq.CacheDeleteCompensationProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, Object> localCache;

    @Resource
    private CacheDeleteCompensationProducer compensationProducer;

    @Value("${hmdp.cache.redis-delete-force-fail:false}")
    private boolean redisDeleteForceFail;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // Redis 缓存必须设置 TTL，即使 MQ 补偿失败，缓存也会自动过期。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 逻辑过期方法保留给旧代码或后续压测对比使用。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithTwoLevelCache(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 先查 Caffeine 本地缓存，命中后不用访问 Redis。
        Object localValue = localCache.getIfPresent(key);
        if (localValue != null) {
            return type.cast(localValue);
        }

        // 2. 本地未命中再查 Redis 分布式缓存。
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            R value = JSONUtil.toBean(json, type);
            localCache.put(key, value);
            return value;
        }
        if (json != null) {
            return null;
        }

        // 3. Redis 也未命中才查 MySQL，查询成功后写入两级缓存。
        R value = dbFallback.apply(id);
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, value, time, unit);
        localCache.put(key, value);
        return value;
    }

    public void deleteCacheAfterDbUpdate(String bizType, Object bizId, String cacheKey) {
        // 数据库更新成功后，先删本地缓存，再删 Redis 缓存；删除失败交给 MQ 补偿。
        localCache.invalidate(cacheKey);
        try {
            if (redisDeleteForceFail) {
                throw new IllegalStateException("当前已开启 Redis 删除失败模拟开关");
            }
            stringRedisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.error("Redis 缓存删除失败，准备发送补偿消息，cacheKey={}", cacheKey, e);
            compensationProducer.send(bizType, String.valueOf(bizId), cacheKey, e.getMessage());
        }
    }

    public <R, ID> R querywithpassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        R value = dbFallback.apply(id);
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, value, time, unit);
        return value;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R querywithlogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R value = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return value;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshValue = dbFallback.apply(id);
                    setWithLogicalExpire(key, freshValue, time, unit);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return value;
    }

    private Boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
