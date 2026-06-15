package com.hmdp.mq;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${hmdp.seckill.order-topic}",
        consumerGroup = "hmdp-seckill-order-consumer",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Override
    public void onMessage(SeckillOrderMessage message) {
        log.info("收到秒杀订单消息，orderId={}, userId={}, voucherId={}",
                message.getId(), message.getUserId(), message.getVoucherId());
        VoucherOrder voucherOrder = BeanUtil.copyProperties(message, VoucherOrder.class);
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }
}