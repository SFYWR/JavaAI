package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
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
    private DroneMapper droneMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private DroneService droneService; // 为了更新无人机状态，通常应调用 Service 而不是 Mapper

    private static final String DRONE_GEO_KEY = "drones:locations";
    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final String ORDER_STATUS_KEY_PREFIX = "order:status:"; // 专门用于高并发原子检测的状态Key

    // 智能电池管理参数
    private static final double DRONE_AVG_SPEED_KMH = 36.0; // 平均时速 km/h
    private static final double POWER_CONSUMPTION_PER_SECOND = 0.05; // 每秒耗电量 (%) (假设值)

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
     * 查找附近的无人机 (包含电量筛选)
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

        // TODO: 在这里可以进一步过滤电量不足的无人机，但 findNearbyDrones 通常用于展示列表
        // 实际的强校验在 grabOrder 中进行
        return droneIds;
    }

    /**
     * 高并发抢单接口 (优化版)
     * 1. 增加智能电池管理校验
     * 2. 使用 Redis Lua 脚本进行原子检查与状态更新 (PENDING -> ASSIGNED)
     * 3. 抢单成功后，发送消息到 RabbitMQ 进行异步落库
     *
     * @param orderId 订单ID
     * @param droneId 抢单的无人机ID
     * @return true 如果抢单成功 (Redis层面), false 如果失败
     */
    public boolean grabOrder(Long orderId, Long droneId) {
        // --- 1. 智能电池管理校验 (Smart Battery Optimization) ---
        // 获取订单详情 (优先查缓存)
        Order order = getOrder(orderId);
        if (order == null) return false;

        // 获取无人机详情 (这里需要查库获取最新电量，或者 Redis 缓存)
        // 假设无人机状态更新不频繁，直接查库简单可靠；如果高频，应读 Redis
        Drone drone = droneMapper.selectById(droneId);
        if (drone == null || drone.getBatteryLevel() == null) return false;

        if (!isBatterySufficient(order, drone)) {
            System.out.println("Drone " + droneId + " battery insufficient for order " + orderId);
            return false;
        }

        // --- 2. 状态原子更新 (Redis Lua) ---
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
            // 缓存 无人机-订单 映射关系，供 WebSocket 实时推送使用
            redisTemplate.opsForValue().set("drone:active_order:" + droneId, orderId, 2, TimeUnit.HOURS);

            // 构造消息体，发送到 MQ 进行异步落库
            // 这里简单发送 Map 包含 orderId 和 droneId
            Map<String, Object> msg = Map.of("orderId", orderId, "droneId", droneId);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_GRAB_ROUTING_KEY, msg);

            // 为了用户体验，也可以在这里预更新一下详情缓存 (Optional)
            order.setStatus("ASSIGNED");
            order.setDroneId(droneId);
            redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, order, 10, TimeUnit.MINUTES);

            return true;
        }

        // 抢单失败
        return false;
    }

    /**
     * 完成订单 (幂等性设计)
     * @param orderId 订单ID
     * @param requestId 客户端请求唯一标识 (防止网络重发)
     */
    @Transactional
    public void completeOrder(Long orderId, String requestId) {
        // 1. 幂等性校验 (Set If Absent)
        // 只有当 requestId 不存在时才执行，key 有效期 10 分钟
        Boolean isFirstRequest = redisTemplate.opsForValue().setIfAbsent(
                "idempotency:complete_order:" + requestId,
                "1",
                10,
                TimeUnit.MINUTES
        );

        if (isFirstRequest == null || !isFirstRequest) {
            // 既然 key 已存在，说明是重复请求，直接返回（视为成功）
            // 或者抛出异常提示
            System.out.println("Duplicate request detected: " + requestId);
            return;
        }

        // 2. 业务逻辑执行
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found");
        }

        if (!"ASSIGNED".equals(order.getStatus())) {
            // 只有已分配的订单才能完成
            // 如果已经是 COMPLETED，可能是上次请求数据库成功但缓存失败导致的，或者是并发问题
            // 由于有幂等性Key挡在前面，这里主要防业务状态不对
            if ("COMPLETED".equals(order.getStatus())) {
                return; // 已经是完成状态
            }
            throw new RuntimeException("Order status is not ASSIGNED");
        }

        // 更新订单状态
        order.setStatus("COMPLETED");
        orderMapper.updateById(order);

        // 释放无人机 (设为 ONLINE)
        Long droneId = order.getDroneId();
        if (droneId != null) {
            droneService.updateStatus(droneId, "ONLINE");
            // 清除活跃订单映射
            redisTemplate.delete("drone:active_order:" + droneId);
        }

        // 更新/清除订单详情缓存
        redisTemplate.delete(ORDER_CACHE_KEY_PREFIX + orderId);

        System.out.println("Order " + orderId + " completed successfully.");
    }

    /**
     * 判断电池是否足够
     * 逻辑：(总距离 / 平均速度) * 耗电因子 < 当前电量
     */
    private boolean isBatterySufficient(Order order, Drone drone) {
        // 计算距离：订单取货点到送货点 (这里简化，假设无人机当前就在取货点附近，或者忽略前往取货点的距离)
        // 更严谨的逻辑应包含：DroneLoc -> PickupLoc -> DeliveryLoc
        // 这里仅计算 Pickup -> Delivery 作为演示
        double distanceKm = calculateDistance(order.getPickupLat(), order.getPickupLon(), order.getDeliveryLat(), order.getDeliveryLon());

        // 估算耗时 (秒) = (距离 km * 1000) / (速度 m/s)
        double speedMs = DRONE_AVG_SPEED_KMH / 3.6;
        double durationSeconds = (distanceKm * 1000) / speedMs;

        // 估算耗电量
        double estimatedConsumption = durationSeconds * POWER_CONSUMPTION_PER_SECOND;

        // 预留 20% 安全电量
        double requiredBattery = estimatedConsumption + 20.0;

        return drone.getBatteryLevel() >= requiredBattery;
    }

    // Haversine formula calculation (simplified via Spring Data Geo if available, or manual)
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // 简单估算，或者使用 org.springframework.data.geo.Distance
        // 这里手动实现一个简版 Haversine
        double R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
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
