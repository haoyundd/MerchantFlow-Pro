package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void handleVoucherOrder(VoucherOrder voucherOrder);

    /** 查询当前用户的单个订单。 */
    Result queryOrder(Long orderId);

    /** 查询当前用户的全部订单。 */
    Result queryMyOrders();

    /** 本地模拟支付。 */
    Result payOrder(Long orderId);

    /** 取消未支付订单并回补库存。 */
    Result cancelOrder(Long orderId);

    /** 定时任务取消超时订单，和手工取消共用事务与补偿逻辑。 */
    boolean cancelTimeoutOrder(Long orderId);

    /** 统一恢复 Redis 和 MySQL 中的秒杀预占。 */
    void restoreReservedStock(VoucherOrder order);
}
