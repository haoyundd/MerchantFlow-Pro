package com.hmdp.task;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Component
public class VoucherOrderTimeoutTask {

    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_CANCELED = 4;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${hmdp.order.timeout-minutes}")
    private long timeoutMinutes;

    @Scheduled(fixedDelayString = "${hmdp.order.timeout-scan-delay-ms}")
    public void cancelTimeoutOrders() {
        // 每次最多处理 100 条，避免单次任务占用过多数据库连接和线程时间。
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<VoucherOrder> orders = voucherOrderService.query()
                .eq("status", STATUS_UNPAID)
                .lt("create_time", deadline)
                .last("LIMIT 100")
                .list();
        for (VoucherOrder order : orders) {
            cancelOneOrder(order);
        }
    }

    private void cancelOneOrder(VoucherOrder order) {
        // 状态更新带上 status=1 条件，防止支付线程和定时取消线程并发时重复处理。
        boolean canceled = voucherOrderService.update()
                .set("status", STATUS_CANCELED)
                .eq("id", order.getId())
                .eq("status", STATUS_UNPAID)
                .update();
        if (!canceled) {
            return;
        }

        // 订单取消成功后回补 MySQL 秒杀库存。
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();

        // Redis 库存和一人一单集合也要回补，保证下次秒杀判断和数据库一致。
        try {
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + order.getVoucherId());
            stringRedisTemplate.opsForSet()
                    .remove(SECKILL_ORDER_KEY + order.getVoucherId(), order.getUserId().toString());
        } catch (Exception e) {
            log.error("超时订单 Redis 库存回补失败，orderId={}", order.getId(), e);
        }
        log.info("已取消超时订单并回补库存，orderId={}, voucherId={}", order.getId(), order.getVoucherId());
    }
}
