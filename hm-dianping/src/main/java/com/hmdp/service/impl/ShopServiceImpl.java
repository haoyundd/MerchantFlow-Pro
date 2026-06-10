package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryById(Long id) {
       //缓存穿透(用工具类解决)
        //Shop shop = querywithpassThrough(id);
       // Shop shop = cacheClient
            //    .querywithpassThrough(CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //用互斥锁解决缓存击穿
        //Shop shop = querywithMutex(id);             //id2->getById(id2)==this::getById(这两个效果一样）
//通过逻辑过期解决缓存击穿（用工具类）
        //Shop shop = querywithlogicalExpire(id);
        Shop shop=cacheClient.querywithlogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
if(shop==null){
    return Result.fail("店铺不存在");
}
        return Result.ok(shop);
    }


    //逻辑过期解决缓存击穿,用工具类实现了
 /* private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//逻辑过期解决缓存击穿
public Shop querywithlogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
       // 2. 未命中缓存直接返回了
    //逻辑过期方案不处理 “缓存未命中” 的数据库查询，只处理 “缓存过期” 的重建。(数据必须提前 “预热”（手动写入）到 Redis。)
        if (StrUtil.isBlank(shopJson)) {
            // 3. 直接返回
            return null;
        }

// 4. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

// 5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1. 未过期，直接返回店铺信息
            return shop;
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
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期的商品信息
       return shop;
        }

    //逻辑过期解决缓存击穿
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));//当前时间加上一个时间段
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/


    //缓存穿透.用工具类实现了
    /*public Shop querywithpassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//转成实体类
            return shop;
        }
        //判断的命中分是不是空值(解决缓存穿透的风险)
        //1. ""表示的之前已经查询到了店铺信息不存在，然后以""形式缓存了
        // ，然后我们现在直接命中了""，就直接返回“店铺不存在”
//2. null 表示当前查询的店铺信息没有缓存，且之前没有查询过，
// 所以店铺信息是否存在我们目前不知道！需要再去查询数据库
        if(shopJson!=null)//不等于null。是为“”，这是空的，就直接返回
        {//返回一个错误信息
            return null;
        }
        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5. 不存在，返回错误
        if (shop == null) {
            //将空值写入redis（缓解缓存穿透）
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        // 6. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }*/

    //加锁解锁放到工具类里面了
    //加锁解锁放到工具类里面了
//    private Boolean tryLock(String key) {//基于redis实现的锁
//        Boolean flag = stringRedisTemplate
//                .opsForValue()
//                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id为空");
        }
        //1.更新数据了
        updateById(shop);

        //2删除缓存//等用户下一次查询的时候，从数据库插入，不然redis缓存都是空的
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }



    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        //1.判断是否需要根据坐标查询（x,y不一定传）
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

// 3. 查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );//这是一个包装类、results.getContent();这是去除元信息，获得能遍历的集合

// 4. 解析出id
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            //没有下一页了
            return Result.ok(Collections.emptyList());
        }
// 4.1. 截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2. 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3. 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

// 5. 根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
// 6. 返回
        return Result.ok(shops);
    }
}
