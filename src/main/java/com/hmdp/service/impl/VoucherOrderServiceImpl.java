package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.observability.MerchantFlowMetrics;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_PAID = 2;
    private static final int STATUS_CANCELED = 4;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private MerchantFlowMetrics merchantFlowMetrics;

    @Value("${hmdp.seckill.order-topic}")
    private String seckillOrderTopic;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill-rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Result validationResult = validateSeckillVoucher(voucherId);
        if (validationResult != null) {
            return validationResult;
        }

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        if (result == null) {
            return Result.fail("秒杀库存校验失败");
        }
        if (result != 0) {
            if (result == 1) {
                return Result.fail("库存不足");
            }
            if (result == 2) {
                return Result.fail("不能重复下单");
            }
            if (result == 3) {
                return Result.fail("秒杀库存尚未预热");
            }
            return Result.fail("秒杀请求无法处理");
        }

        SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);
        try {
            rocketMQTemplate.convertAndSend(seckillOrderTopic, message);
        } catch (Exception e) {
            // RocketMQ 投递失败时立即回滚 Redis 预占，避免“接口报错但库存永久减少”。
            rollbackSeckillReservation(voucherId, userId);
            merchantFlowMetrics.recordMqPublishFailure(MerchantFlowMetrics.FLOW_SECKILL_ORDER);
            log.error("秒杀订单消息发送失败，已尝试回滚 Redis 预占，orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId, e);
            return Result.fail("下单失败，请稍后重试");
        }

        return Result.ok(orderId);
    }

    /** 使用 Lua 原子恢复库存和一人一单标记，避免回滚过程被并发请求打断。 */
    private void rollbackSeckillReservation(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    java.util.Arrays.asList(
                            "seckill:stock:" + voucherId,
                            "seckill:order:" + voucherId
                    ),
                    userId.toString()
            );
        } catch (Exception rollbackException) {
            // 定时对账任务会继续兜底，不能覆盖最初的 RocketMQ 发送异常。
            log.error("秒杀 Redis 预占回滚失败，voucherId={}, userId={}", voucherId, rollbackException);
        }
    }

    /**
     * 秒杀 Lua 只负责并发扣减，活动状态必须在业务层校验，避免过期优惠券仍能被 Redis 库存放行。
     */
    private Result validateSeckillVoucher(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getType())) {
            return Result.fail("秒杀券不存在");
        }
        if (!Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("秒杀券当前不可用");
        }

        com.hmdp.entity.SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀活动不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (seckillVoucher.getBeginTime() != null && now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀活动尚未开始");
        }
        if (seckillVoucher.getEndTime() != null && !now.isBefore(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀活动已结束");
        }
        return null;
    }

    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次了");
            return;
        }

        boolean success = seckillVoucherService
                .update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 消费成功后才落库为待支付订单，下一步由本地支付接口推进状态。
        voucherOrder.setStatus(STATUS_UNPAID)
                .setPayType(1)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        save(voucherOrder);
    }

    /** 查询订单时绑定当前用户，避免订单 ID 被用于越权读取。 */
    @Override
    public Result queryOrder(Long orderId) {
        VoucherOrder order = query().eq("id", orderId)
                .eq("user_id", UserHolder.getUser().getId()).one();
        return order == null ? Result.fail("订单不存在") : Result.ok(order);
    }

    /** 查询当前用户订单，并按创建时间倒序返回。 */
    @Override
    public Result queryMyOrders() {
        List<VoucherOrder> orders = query().eq("user_id", UserHolder.getUser().getId())
                .orderByDesc("create_time").list();
        return Result.ok(orders);
    }

    /** 本地模拟支付只执行状态转换，不调用外部支付平台。 */
    @Override
    @Transactional
    public Result payOrder(Long orderId) {
        VoucherOrder order = query().eq("id", orderId)
                .eq("user_id", UserHolder.getUser().getId()).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!Integer.valueOf(STATUS_UNPAID).equals(order.getStatus())) {
            return Result.fail("订单当前状态不可支付");
        }
        boolean updated = update().set("status", STATUS_PAID)
                .set("pay_time", LocalDateTime.now())
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId).eq("user_id", UserHolder.getUser().getId())
                .eq("status", STATUS_UNPAID).update();
        return updated ? Result.ok(orderId) : Result.fail("订单状态已变化，请刷新后重试");
    }

    /** 只有待支付订单可以取消，条件更新保证重复取消不会重复回补。 */
    @Override
    @Transactional
    public Result cancelOrder(Long orderId) {
        VoucherOrder order = query().eq("id", orderId)
                .eq("user_id", UserHolder.getUser().getId()).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        boolean canceled = update().set("status", STATUS_CANCELED)
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId).eq("user_id", UserHolder.getUser().getId())
                .eq("status", STATUS_UNPAID).update();
        if (!canceled) {
            return Result.fail("订单当前状态不可取消");
        }
        restoreReservedStock(order);
        return Result.ok(orderId);
    }

    /**
     * 定时任务专用取消入口。
     *
     * <p>Redis 补偿失败会抛出异常，让数据库事务回滚，下一轮任务仍可重试。</p>
     */
    @Override
    @Transactional
    public boolean cancelTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || !Integer.valueOf(STATUS_UNPAID).equals(order.getStatus())) {
            return false;
        }
        boolean canceled = update().set("status", STATUS_CANCELED)
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId).eq("status", STATUS_UNPAID).update();
        if (!canceled) {
            return false;
        }
        restoreReservedStock(order);
        return true;
    }

    /** 统一恢复数据库库存与 Redis 预占，Redis Lua 保证集合和库存原子变化。 */
    @Override
    public void restoreReservedStock(VoucherOrder order) {
        seckillVoucherService.update().setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId()).update();
        stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                java.util.Arrays.asList(
                        "seckill:stock:" + order.getVoucherId(),
                        "seckill:order:" + order.getVoucherId()),
                order.getUserId().toString());
    }
}
