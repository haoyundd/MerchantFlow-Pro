package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_LIST;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_LIST_TTL;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * MerchantFlow 的 Redis 数据预热和一致性校准任务。
 *
 * <p>Redis 中的数据分为两类：店铺缓存、GEO、店铺类型列表属于可以从 MySQL
 * 完整重建的派生数据；登录态、Feed、聊天会话等属于用户运行态数据，本任务
 * 不会扫描或删除它们。秒杀库存比较特殊，它既是缓存也是并发协调状态，只有
 * 找不到对应 MySQL 订单的孤立预占才会自动回补。</p>
 *
 * <p>下一步：继续在秒杀请求链路中增加 RocketMQ 发送失败的即时回滚，
 * 本任务作为进程崩溃和历史脏数据的最终兜底。</p>
 */
@Slf4j
@Component
public class RedisDataReconcileTask {

    private static final String RECONCILE_LOCK_KEY = "lock:merchantflow:redis-reconcile";
    private static final long RECONCILE_LOCK_TTL_SECONDS = 60L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Value("${hmdp.cache.reconcile.enabled:true}")
    private boolean enabled;

    /** 应用启动完成后预热可重建数据，避免首次访问才发现 GEO 缓存缺失。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAfterApplicationReady() {
        reconcile("startup");
    }

    /** 定时兜底历史脏数据，默认间隔较长，避免持续扫描生产数据。 */
    @Scheduled(fixedDelayString = "${hmdp.cache.reconcile-delay-ms:300000}")
    public void reconcilePeriodically() {
        reconcile("scheduled");
    }

    /**
     * 使用 Redis 分布式锁串行执行校准，避免多个应用实例同时重建 GEO 和秒杀库存。
     */
    private void reconcile(String trigger) {
        if (!enabled) {
            log.info("Redis 数据校准已关闭，trigger={}", trigger);
            return;
        }

        String lockToken = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                RECONCILE_LOCK_KEY,
                lockToken,
                RECONCILE_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("已有其他实例执行 Redis 数据校准，trigger={}", trigger);
            return;
        }

        try {
            warmShopTypeList();
            rebuildShopGeo();
            reconcileSeckillState();
            log.info("Redis 数据校准完成，trigger={}", trigger);
        } catch (Exception e) {
            // 校准失败不能阻断主应用启动，后续定时任务会再次尝试。
            log.error("Redis 数据校准失败，trigger={}", trigger, e);
        } finally {
            // 当前任务设置了足够长的锁 TTL，且每次执行都受单实例串行保护。
            stringRedisTemplate.delete(RECONCILE_LOCK_KEY);
        }
    }

    /** 从 MySQL 重建店铺类型列表，并补上物理 TTL。 */
    private void warmShopTypeList() {
        List<ShopType> shopTypes = shopTypeService.query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(
                CACHE_TYPE_LIST,
                JSONUtil.toJsonStr(shopTypes),
                CACHE_TYPE_LIST_TTL,
                TimeUnit.MINUTES
        );
        log.debug("已预热店铺类型列表，size={}", shopTypes.size());
    }

    /**
     * 按店铺类型重建 GEO。先删除已知类型的旧集合，再写入 MySQL 中具有有效坐标的店铺。
     * 只操作 shop:geo:* 这类派生键，不会影响登录、Feed 和聊天数据。
     */
    private void rebuildShopGeo() {
        List<Shop> shops = shopService.list();
        List<ShopType> shopTypes = shopTypeService.list();
        Set<Long> typeIds = new HashSet<>();
        for (ShopType shopType : shopTypes) {
            typeIds.add(shopType.getId());
        }

        Map<Long, List<RedisGeoCommands.GeoLocation<String>>> locationsByType = new HashMap<>();
        for (Shop shop : shops) {
            if (shop.getTypeId() == null) {
                continue;
            }
            typeIds.add(shop.getTypeId());
            if (shop.getX() == null || shop.getY() == null) {
                continue;
            }
            locationsByType.computeIfAbsent(shop.getTypeId(), ignored -> new ArrayList<>())
                    .add(new RedisGeoCommands.GeoLocation<>(
                            String.valueOf(shop.getId()),
                            new Point(shop.getX(), shop.getY())
                    ));
        }

        for (Long typeId : typeIds) {
            String key = SHOP_GEO_KEY + typeId;
            stringRedisTemplate.delete(key);
            List<RedisGeoCommands.GeoLocation<String>> locations = locationsByType.get(typeId);
            if (locations != null && !locations.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, locations);
            }
        }
        log.debug("已重建店铺 GEO，shopCount={}, geoTypeCount={}", shops.size(), locationsByType.size());
    }

    /**
     * 校准秒杀库存：先清理没有 MySQL 订单的孤立预占，再对活动中的库存进行补齐。
     * 过期活动不重新创建库存 Key，但仍清理本次实验残留的孤立用户标记。
     */
    private void reconcileSeckillState() {
        LocalDateTime now = LocalDateTime.now();
        for (SeckillVoucher seckillVoucher : seckillVoucherService.list()) {
            Long voucherId = seckillVoucher.getVoucherId();
            repairOrphanReservations(voucherId);

            Voucher voucher = voucherService.getById(voucherId);
            boolean active = voucher != null
                    && Integer.valueOf(1).equals(voucher.getStatus())
                    && seckillVoucher.getBeginTime() != null
                    && seckillVoucher.getEndTime() != null
                    && !now.isBefore(seckillVoucher.getBeginTime())
                    && now.isBefore(seckillVoucher.getEndTime());
            if (!active) {
                continue;
            }

            String stockKey = SECKILL_STOCK_KEY + voucherId;
            String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
            String mysqlStock = String.valueOf(Math.max(0, seckillVoucher.getStock()));
            if (redisStock == null || !mysqlStock.equals(redisStock)) {
                // 校准任务在先清理孤立预占后执行，确保 Redis 与 MySQL 剩余库存回到同一基线。
                stringRedisTemplate.opsForValue().set(stockKey, mysqlStock);
                log.warn("已校准秒杀库存，voucherId={}, mysqlStock={}, oldRedisStock={}",
                        voucherId, mysqlStock, redisStock);
            }
        }
    }

    /** 清理 Redis 一人一单集合中没有对应 MySQL 订单的用户预占，并回补一份库存。 */
    private void repairOrphanReservations(Long voucherId) {
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        Set<String> members = stringRedisTemplate.opsForSet().members(orderKey);
        if (members == null || members.isEmpty()) {
            return;
        }

        for (String member : members) {
            try {
                Long userId = Long.valueOf(member);
                long orderCount = voucherOrderService.count(new QueryWrapper<VoucherOrder>()
                        .eq("user_id", userId)
                        .eq("voucher_id", voucherId));
                if (orderCount == 0) {
                    stringRedisTemplate.opsForSet().remove(orderKey, member);
                    stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
                    log.warn("已清理孤立秒杀预占，voucherId={}, userId={}", voucherId, userId);
                }
            } catch (NumberFormatException e) {
                // 非法成员不是有效用户，删除后回补对应的库存预占。
                stringRedisTemplate.opsForSet().remove(orderKey, member);
                stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
                log.warn("已清理非法秒杀预占成员，voucherId={}, member={}", voucherId, member);
            }
        }
    }
}
