package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 新增店铺后立即写入 GEO，避免店铺详情已经存在但附近店铺查询看不到新数据。
     * 下一步：如果扩展到多实例生产环境，可把 GEO 更新改为事务提交后的事件消息。
     */
    @Override
    public boolean save(Shop shop) {
        boolean saved = super.save(shop);
        if (saved) {
            refreshGeoAfterWrite(null, shop);
        }
        return saved;
    }

    @Override
    public Result queryById(Long id) {
        // 查询顺序：Caffeine 本地缓存 -> Redis 分布式缓存 -> MySQL，MySQL 命中后写回两级缓存。
        Shop shop = cacheClient.queryWithTwoLevelCache(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺 id 不能为空");
        }
        // 先读取旧类型，店铺可能发生 typeId 迁移，后续需要同时清理旧 GEO 集合。
        Shop oldShop = getById(id);

        // 先更新 MySQL，数据库成功后再删除两级缓存，保证 MySQL 是真实数据源。
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺更新失败");
        }
        cacheClient.deleteCacheAfterDbUpdate("SHOP", id, CACHE_SHOP_KEY + id);
        // updateById 支持部分字段更新，必须重新读取完整店铺再重建 GEO。
        refreshGeoAfterWrite(oldShop, getById(id));
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 未传坐标时，直接按店铺类型分页查询 MySQL。
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 传入坐标时，先用 Redis GEO 做距离排序和分页。
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        Boolean geoExists = stringRedisTemplate.hasKey(key);
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null) {
            return Boolean.TRUE.equals(geoExists)
                    ? Result.ok(Collections.emptyList())
                    : queryShopWithoutGeo(typeId, current);
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list == null || list.isEmpty()) {
            // GEO key 缺失时降级到 MySQL；key 存在但附近没有店铺时保持距离查询语义，返回空列表。
            return Boolean.TRUE.equals(geoExists)
                    ? Result.ok(Collections.emptyList())
                    : queryShopWithoutGeo(typeId, current);
        }
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    /** GEO 尚未预热时的安全降级路径，确保接口不会因为缓存缺失直接返回空。 */
    private Result queryShopWithoutGeo(Integer typeId, Integer current) {
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    /**
     * 店铺写入后同步维护 GEO。更新时先删除旧类型集合中的成员，再写入新坐标。
     * 没有有效坐标的店铺只保留在 MySQL，不写入 Redis GEO。
     */
    private void refreshGeoAfterWrite(Shop oldShop, Shop newShop) {
        if (oldShop != null && oldShop.getTypeId() != null) {
            stringRedisTemplate.delete(SHOP_GEO_KEY + oldShop.getTypeId());
        }
        if (newShop.getTypeId() != null) {
            stringRedisTemplate.delete(SHOP_GEO_KEY + newShop.getTypeId());
        }
        if (newShop.getTypeId() == null || newShop.getX() == null || newShop.getY() == null) {
            return;
        }

        // 重新写入对应类型的全部店铺，避免只增加当前店铺导致旧成员状态不完整。
        List<Shop> shops = query().eq("type_id", newShop.getTypeId()).list();
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
        for (Shop shop : shops) {
            if (shop.getX() != null && shop.getY() != null) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        String.valueOf(shop.getId()),
                        new org.springframework.data.geo.Point(shop.getX(), shop.getY())
                ));
            }
        }
        if (!locations.isEmpty()) {
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + newShop.getTypeId(), locations);
        }
    }
}
