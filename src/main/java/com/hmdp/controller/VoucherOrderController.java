package com.hmdp.controller;


import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitType;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @PostMapping("seckill/{id}")
    @RateLimit(max = 5, windowSeconds = 10, type = RateLimitType.USER)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 查询订单，服务层会校验当前用户归属。 */
    @GetMapping("/{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }

    /** 查询当前用户订单列表。 */
    @GetMapping("/my")
    public Result queryMyOrders() {
        return voucherOrderService.queryMyOrders();
    }

    /** 本地模拟支付，不连接真实第三方支付平台。 */
    @PostMapping("/{id}/pay")
    public Result pay(@PathVariable("id") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }

    /** 取消未支付订单并幂等回补库存。 */
    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }
}
