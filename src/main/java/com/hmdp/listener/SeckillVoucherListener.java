package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class SeckillVoucherListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = "QA")
    public void receivedA(Message message, Channel channel) {
        VoucherOrder voucherOrder = JSONUtil.toBean(new String(message.getBody()), VoucherOrder.class);
        log.info("consume voucher order from queueA, orderId={}", voucherOrder.getId());
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }

    @RabbitListener(queues = "QD")
    public void receivedD(Message message) {
        VoucherOrder voucherOrder = JSONUtil.toBean(new String(message.getBody()), VoucherOrder.class);
        log.warn("consume voucher order from dead-letter queue, orderId={}", voucherOrder.getId());
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }
}
