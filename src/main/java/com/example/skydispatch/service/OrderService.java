package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.OrderMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务类
 * 包含创建订单、查找附近无人机、高并发抢单逻辑
 */
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String DRONE_GEO_KEY = "drones:locations";
    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final String ORDER_STATUS_KEY_PREFIX = "order:status:"; // 专门用于高并发原子检测的状态Key

    /**
     * 创建订单
     * 将订单数据存入 MySQL，并预热 Redis 缓存，同时设置订单超时检查
     */
    public Order createOrder(String description, double pickupLat, double pickupLon, double deliveryLat, double deliveryLon) {
        Order order = new Order();
        order.setDescription(description);
        order.setPickupLat(pickupLat);
        order.setPickupLon(pickupLon);
        order.setDeliveryLat(deliveryLat);
        order.setDeliveryLon(deliveryLon);
        order.setStatus("PENDING");
        orderMapper.insert(order);

        // Cache Aside 模式：写入数据库后，更新缓存
        redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10, TimeUnit.MINUTES);

        // 设置 Redis 简单状态 Key，用于抢单时的 Lua 原子检测
        // 格式: order:status:1 -> "PENDING"
        redisTemplate.opsForValue().set(ORDER_STATUS_KEY_PREFIX + order.getId(), "PENDING", 30, TimeUnit.MINUTES);

        // 发送消息到“延迟队列”，用于超时未接单自动取消 (例如 30秒后)
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY, order.getId());

        return order;
    }

    /**
     * 查找附近的无人机
     */
    public List<String> findNearbyDrones(double lat, double lon, double radiusKm) {
        Circle circle = new Circle(new Point(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS));
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending();
        GeoResults<RedisGeoCommands.GeoLocation<Object>> results = redisTemplate.opsForGeo().radius(DRONE_GEO_KEY, circle, args);

        List<String> droneIds = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<Object>> result : results) {
                droneIds.add(result.getContent().getName().toString());
            }
        }
        return droneIds;
    }

    /**
     * 高并发抢单接口 (优化版)
     * 1. 使用 Redis Lua 脚本进行原子检查与状态更新 (PENDING -> ASSIGNED)
     * 2. 抢单成功后，发送消息到 RabbitMQ 进行异步落库
     *
     * @param orderId 订单ID
     * @param droneId 抢单的无人机ID
     * @return true 如果抢单成功 (Redis层面), false 如果失败
     */
    public boolean grabOrder(Long orderId, Long droneId) {
        // Lua 脚本：检查状态是否为 PENDING，如果是，则更新为 ASSIGNED 并返回 1，否则返回 0
        String script =
                "if redis.call('get', KEYS[1]) == 'PENDING' then " +
                "   redis.call('set', KEYS[1], 'ASSIGNED'); " +
                "   return 1; " +
                "else " +
                "   return 0; " +
                "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        String key = ORDER_STATUS_KEY_PREFIX + orderId;
        Long result = redisTemplate.execute(redisScript, Collections.singletonList(key));

        if (result != null && result == 1) {
            // Redis 抢单成功
            // 构造消息体，发送到 MQ 进行异步落库
            // 这里简单发送 Map 包含 orderId 和 droneId
            Map<String, Object> msg = Map.of("orderId", orderId, "droneId", droneId);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_GRAB_ROUTING_KEY, msg);

            // 为了用户体验，也可以在这里预更新一下详情缓存 (Optional)
            Order cachedOrder = (Order) redisTemplate.opsForValue().get(ORDER_CACHE_KEY_PREFIX + orderId);
            if (cachedOrder != null) {
                cachedOrder.setStatus("ASSIGNED");
                cachedOrder.setDroneId(droneId);
                redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, cachedOrder, 10, TimeUnit.MINUTES);
            }

            return true;
        }

        // 抢单失败
        return false;
    }

    /**
     * 获取订单详情
     */
    public Order getOrder(Long orderId) {
        String key = ORDER_CACHE_KEY_PREFIX + orderId;
        Order order = (Order) redisTemplate.opsForValue().get(key);
        if (order == null) {
            order = orderMapper.selectById(orderId);
            if (order != null) {
                redisTemplate.opsForValue().set(key, order, 10, TimeUnit.MINUTES);
            }
        }
        return order;
    }

    /**
     * 处理 MQ 抢单消息的具体业务逻辑 (供 Listener 调用)
     * 使用数据库乐观锁作为最终一致性保障
     */
    @Transactional
    public void processGrabOrder(Long orderId, Long droneId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PENDING".equals(order.getStatus())) {
            order.setStatus("ASSIGNED");
            order.setDroneId(droneId);
            int rows = orderMapper.updateById(order);
            if (rows > 0) {
                // 落库成功，更新缓存
                redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, order, 10, TimeUnit.MINUTES);
                System.out.println("Order " + orderId + " grabbed by drone " + droneId + " persisted to DB.");
            } else {
                 System.out.println("Order " + orderId + " grab failed optimistic lock check.");
            }
        } else {
             System.out.println("Order " + orderId + " is not PENDING in DB, skipping grab.");
        }
    }

    /**
     * 处理订单超时逻辑 (供 Listener 调用)
     */
    @Transactional
    public void processOrderTimeout(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PENDING".equals(order.getStatus())) {
            // 超时未接单，取消订单
            order.setStatus("CANCELLED"); // 或者 EXPIRED
            orderMapper.updateById(order);

            // 更新缓存和 Redis 状态
            redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, order, 10, TimeUnit.MINUTES);
            redisTemplate.delete(ORDER_STATUS_KEY_PREFIX + orderId); // 删除抢单状态Key

            System.out.println("Order " + orderId + " has expired and is cancelled.");
        }
    }
}
