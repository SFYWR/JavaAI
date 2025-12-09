package com.example.skydispatch.listener;

import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 消息消费者
 * 负责处理异步的抢单落库请求以及订单超时逻辑
 */
@Component
public class OrderMQListener {

    @Autowired
    private OrderService orderService;

    /**
     * 监听抢单队列 (ORDER_GRAB_QUEUE)
     * 目的：削峰填谷，异步处理数据库写操作
     * 逻辑：接收包含 orderId 和 droneId 的消息，调用 service 更新数据库状态
     */
    @RabbitListener(queues = RabbitConfig.ORDER_GRAB_QUEUE)
    public void handleGrabOrder(Map<String, Object> msg) {
        try {
            Long orderId = Long.valueOf(msg.get("orderId").toString());
            Long droneId = Long.valueOf(msg.get("droneId").toString());
            System.out.println("Received grab request for order: " + orderId);
            orderService.processGrabOrder(orderId, droneId);
        } catch (Exception e) {
            e.printStackTrace();
            // 在生产环境中，这里应该有重试机制或记录到死信队列
        }
    }

    /**
     * 监听死信/超时队列 (ORDER_TIMEOUT_QUEUE)
     * 来源：Delayed Queue (延迟队列) 过期后转发的消息
     * 目的：处理订单超时（如支付超时、无人接单超时）
     * 逻辑：检查订单当前状态，如果仍处于未完成状态（UNPAID/PENDING），则取消订单
     */
    @RabbitListener(queues = RabbitConfig.ORDER_TIMEOUT_QUEUE)
    public void handleOrderTimeout(Long orderId) {
        try {
            System.out.println("Checking timeout for order: " + orderId);
            orderService.processOrderTimeout(orderId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
