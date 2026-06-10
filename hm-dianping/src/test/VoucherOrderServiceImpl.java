package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
@Resource
 private RedisIdWorker redisIdWorker;
  @Resource
    private ISeckillVoucherService seckillVoucherService;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SEKKILL_SCRIPT;
    static {
        SEKKILL_SCRIPT = new DefaultRedisScript<>();
        SEKKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SEKKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);




    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
// 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SEKKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
// 2. 判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            // 2.1. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
// 2.2. 为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");//全局唯一订单id
// TODO 保存阻塞队列
        // 2,3 创建订单,放入订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        // 2.4用户id
        voucherOrder.setUserId(userId);
        // 2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6放入阻塞队列2.6
        orderTasks.add(voucherOrder);



// 3. 返回订单id
        return Result.ok(orderId);
    }
   // @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//
//// 3. 判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已经结束
//            return Result.fail("秒杀已经结束！");
//        }
//
//// 4. 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();//拦截器里面存有用户信息
//       //创建锁对象。同一个用户加锁，
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //用reidsson创建锁对象
//        RLock lock= redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = false;
//        try {
//            isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        //判断是否获取锁成功
//        if(!isLock){
//            //获取锁失败，返回错误
//            return Result.fail("不允许重复下单");
//
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);//用动态代理，是为了让事务注解@Transactional生效
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

  //===============================================//
    @Transactional
    public  Result creatVoucherOrder(Long voucherId) {
        //5一人一单
        Long userId = UserHolder.getUser().getId();//拦截器里面存有用户信息


        //5.1查询该用户的订单
        int count= query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count>0) {
            //用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }
        // 6. 扣减库存
        boolean success = seckillVoucherService.update()//开启update更新操作
                .setSql("stock = stock - 1")//setSql() 方法的特点：直接接收一段「原生 SQL 片段」，
                // 不做额外解析，适合处理「字段自增 / 自减」「字段拼接」等复杂更新逻辑。
                //"stock = stock - 1" 就是原生 SQL 片段，含义是「把当前记录的 stock 字段值减 1」。
                //如果是简单的 “给字段设置固定值”，不会用 setSql()，而是用 .set() 方法
                .eq("voucher_id", voucherId).gt("stock", 0)
                //对应 SQL 中的 WHERE 子句，eq 是「equal（等于）」的缩写
                //第一个参数 "voucher_id"：对应数据库表 tb_seckill_voucher 中的字段名（也对应 SeckillVoucher 实体类中的 voucherId 字段）；
                //第二个参数 voucherId：传入的具体条件值（秒杀优惠券 ID）；
                .update();//把前面链式拼接的更新内容和条件，转换成完整的 SQL 语句；
        //执行该 SQL 并获取「影响的行数」；
        //返回一个 boolean 结果：影响行数 > 0 则返回 true，否则返回 false。
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        long orderId = redisIdWorker.nextId("order");//用唯一id设置订单Id
        voucherOrder.setId(orderId);
        // 7.2 用户id

        voucherOrder.setUserId(userId);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 8. 返回订单id
        return Result.ok(orderId);
    }

}
