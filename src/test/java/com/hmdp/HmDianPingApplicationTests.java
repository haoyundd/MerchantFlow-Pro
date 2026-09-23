package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.dto.Result;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.ShopTypeServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@ActiveProfiles("test")
class HmDianPingApplicationTests {
@Resource
    private ShopServiceImpl shopService;
    @Resource
    private ShopTypeServiceImpl shopTypeService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

//@Test

  @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

   // @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    //@Test
    void testSaveShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

   // @Test
    void loadshopDate() {
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();

// 2. 把店铺分组，按照typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

// 3. 分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1. 获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;

            // 3.2. 获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            // 3.3. 写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }

            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }

    @Test
    void testHyperLogLog() {
        String testKey = "test:hl2:" + UUID.randomUUID();
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add(testKey, values);
            }
        }

        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size(testKey);
        try {
            org.junit.jupiter.api.Assertions.assertTrue(count > 950_000 && count < 1_050_000);
        } finally {
            // 测试即使断言失败也必须清理临时 Key，避免污染测试 Redis DB。
            stringRedisTemplate.delete(testKey);
        }
    }

    @Test
    void testShopTypeCacheHasTtl() {
        try {
            Result result = shopTypeService.queryList();
            Assertions.assertTrue(result.getSuccess());
            Long ttlSeconds = stringRedisTemplate.getExpire("cache:shoplist:", TimeUnit.SECONDS);
            Assertions.assertTrue(ttlSeconds > 0 && ttlSeconds <= 1800);
        } finally {
            stringRedisTemplate.delete("cache:shoplist:");
        }
    }

    @Test
    void testGeoQueryFallsBackWhenGeoCacheMissing() {
        String key = "shop:geo:1";
        try {
            stringRedisTemplate.delete(key);
            Result result = shopService.queryShopByType(1, 1, 120.15, 30.33);
            Assertions.assertTrue(result.getSuccess());
            Assertions.assertNotNull(result.getData());
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void testSeckillLuaReturnsMissingStockInsteadOfThrowing() {
        String stockKey = "test:seckill:stock:" + UUID.randomUUID();
        String orderKey = "test:seckill:order:" + UUID.randomUUID();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        try {
            Long result = stringRedisTemplate.execute(script, Arrays.asList(stockKey, orderKey), "1", "1", "1");
            Assertions.assertEquals(3L, result);
        } finally {
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    @Test
    void testSeckillRollbackLuaIsAtomicAndIdempotent() {
        String stockKey = "test:seckill:stock:" + UUID.randomUUID();
        String orderKey = "test:seckill:order:" + UUID.randomUUID();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill-rollback.lua"));
        script.setResultType(Long.class);
        try {
            stringRedisTemplate.opsForValue().set(stockKey, "19");
            stringRedisTemplate.opsForSet().add(orderKey, "1");
            Long first = stringRedisTemplate.execute(script, Arrays.asList(stockKey, orderKey), "1");
            Long second = stringRedisTemplate.execute(script, Arrays.asList(stockKey, orderKey), "1");
            Assertions.assertEquals(1L, first);
            Assertions.assertEquals(0L, second);
            Assertions.assertEquals("20", stringRedisTemplate.opsForValue().get(stockKey));
            Assertions.assertEquals(0L, stringRedisTemplate.opsForSet().size(orderKey));
        } finally {
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }
}
