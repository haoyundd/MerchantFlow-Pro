package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //物理过期、并将java对象序列化为json并存储sting类型的key中
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //逻辑过期。并将java对象序列化为json并存储sting类型的key中
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    //缓存穿透
    public <R,ID> R querywithpassThrough(String KeyPrefix ,ID id,Class<R> type,Function<ID,R> dbFallback
    ,Long time, TimeUnit unit) {
        String key = KeyPrefix + id;

        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);//转成实体类
        }
        //判断的命中分是不是空值(解决缓存穿透的风险)
        //1. ""表示的之前已经查询到了店铺信息不存在，然后以""形式缓存了
        // ，然后我们现在直接命中了""，就直接返回“店铺不存在”
//2. null 表示当前查询的店铺信息没有缓存，且之前没有查询过，
// 所以店铺信息是否存在我们目前不知道！需要再去查询数据库
        if(json!=null)//不等于null。是为“”，这是空的，就直接返回
        {//返回一个错误信息
            return null;
        }
        // 4. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        // 5. 不存在，返回错误
        if (r == null) {
            //将空值写入redis（缓解缓存穿透）
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        // 6. 存在，写入redis
        this.set(key, r, time, unit);
        // 7. 返回
        return r;
    }
//============================================================================//
    //============================================================================//

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿
    public <R,ID> R querywithlogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {//keyPrefix是前缀，拼接key的
        String key = keyPrefix + id;

        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 未命中缓存直接返回了
        //逻辑过期方案不处理 “缓存未命中” 的数据库查询，只处理 “缓存过期” 的重建。(数据必须提前 “预热”（手动写入）到 Redis。)
        if (StrUtil.isBlank(json)) {
            // 3. 直接返回
            return null;
        }

// 4. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//原来是shop.class
        LocalDateTime expireTime = redisData.getExpireTime();

// 5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1. 未过期，直接返回店铺信息
            return r;
        }

// 5.2. 已过期，需要缓存重建
// 6. 缓存重建
// 6.1. 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 6.2. 判断是否获取锁成功
        if (isLock) {//当线程没拿到锁，直接跳过这个 if 块，去返回旧数据。
            // 6.3. 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存(两步走，1.查询数据库，2.然后存进去，。写入redis)
                   // this.saveShop2Redis(id, 20L);不用这个写好的方法了
                    //1.查询数据库。用函数式接口
                    R r1=dbFallback.apply(id);
                    //2.写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期的商品信息
        return r;
    }

    private Boolean tryLock(String key) {//基于redis实现的锁
        Boolean flag = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
