package com.example.skydispatch.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置类
 * 定义交换机、队列及绑定关系（包含延迟队列和死信队列逻辑）
 */
@Configuration
public class RabbitConfig {

    // 1. 普通订单/抢单交换机
    public static final String ORDER_EXCHANGE = "order.exchange";

    // 2. 死信交换机 (DLX)
    public static final String DLX_EXCHANGE = "dlx.exchange";

    // 3. 抢单队列（用于异步处理抢单逻辑）
    public static final String ORDER_GRAB_QUEUE = "order.grab.queue";
    public static final String ORDER_GRAB_ROUTING_KEY = "order.grab";

    // 4. 延迟队列（用于订单超时检测）
    // 消息发送到此队列，不设置消费者，TTL 过期后转发到死信队列
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";

    // 5. 死信队列（实际处理超时逻辑的队列）
    // 绑定到死信交换机
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";

    // 6. 支付超时延迟队列
    public static final String PAYMENT_DELAY_QUEUE = "payment.delay.queue";
    public static final String PAYMENT_DELAY_ROUTING_KEY = "payment.delay";

    // 定义普通交换机
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    // 定义死信交换机
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    // 定义抢单队列
    @Bean
    public Queue orderGrabQueue() {
        return new Queue(ORDER_GRAB_QUEUE);
    }

    // 定义延迟队列 (配置 TTL 和 DLX)
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 设置死信交换机
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        // 设置死信 Routing Key
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_ROUTING_KEY);
        // 设置队列 TTL (这里演示设为 30秒方便测试，实际可能较长)
        args.put("x-message-ttl", 30000);
        return QueueBuilder.durable(ORDER_DELAY_QUEUE).withArguments(args).build();
    }

    // 定义支付延迟队列 (配置 TTL 5分钟 和 DLX)
    @Bean
    public Queue paymentDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 设置死信交换机 (复用同一个 DLX)
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        // 设置死信 Routing Key (指向超时处理队列)
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_ROUTING_KEY);
        // 设置队列 TTL 5分钟
        args.put("x-message-ttl", 5 * 60 * 1000);
        return QueueBuilder.durable(PAYMENT_DELAY_QUEUE).withArguments(args).build();
    }

    // 定义死信队列 (处理超时，包括未接单超时和支付超时)
    @Bean
    public Queue orderTimeoutQueue() {
        return new Queue(ORDER_TIMEOUT_QUEUE);
    }

    // 绑定抢单队列
    @Bean
    public Binding bindingGrabQueue() {
        return BindingBuilder.bind(orderGrabQueue()).to(orderExchange()).with(ORDER_GRAB_ROUTING_KEY);
    }

    // 绑定延迟队列
    @Bean
    public Binding bindingDelayQueue() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }

    // 绑定支付延迟队列
    @Bean
    public Binding bindingPaymentDelayQueue() {
        return BindingBuilder.bind(paymentDelayQueue()).to(orderExchange()).with(PAYMENT_DELAY_ROUTING_KEY);
    }

    // 绑定死信队列
    @Bean
    public Binding bindingTimeoutQueue() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(dlxExchange()).with(ORDER_TIMEOUT_ROUTING_KEY);
    }
}
