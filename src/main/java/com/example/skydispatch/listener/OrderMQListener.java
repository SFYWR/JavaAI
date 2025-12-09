package com.example.skydispatch.listener;

import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 消息消费者
 * 处理抢单持久化和订单超时逻辑
 */
@Component
public class OrderMQListener {

    @Autowired
    private OrderService orderService;

    /**
     * 监听抢单队列，异步处理数据库更新
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
     * 监听死信/超时队列，处理订单过期
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
