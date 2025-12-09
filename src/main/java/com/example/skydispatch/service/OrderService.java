package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.mapper.OrderMapper;
import com.example.skydispatch.util.CacheClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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
    private DroneService droneService;

    @Autowired
    private CacheClient cacheClient;

    private static final String DRONE_GEO_KEY = "drones:locations";
    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final String ORDER_STATUS_KEY_PREFIX = "order:status:";
    // 匹配池 Key
    private static final String MATCH_POOL_KEY = "order:matchmaking:pool";

    // 智能电池管理参数
    private static final double DRONE_AVG_SPEED_KMH = 36.0;
    private static final double POWER_CONSUMPTION_PER_SECOND = 0.05;

    /**
     * 创建订单 (派单模式)
     * 将订单数据存入 MySQL，缓存，并加入匹配池
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

        // Cache Aside 模式：写入数据库后，更新缓存 (使用 CacheClient 封装的随机TTL方法)
        cacheClient.setWithRandomTtl(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10L, TimeUnit.MINUTES);

        // 加入布隆过滤器 (防止穿透)
        cacheClient.addToBloomFilter(order.getId());

        // 设置 Redis 状态，用于防止后续重复分配
        redisTemplate.opsForValue().set(ORDER_STATUS_KEY_PREFIX + order.getId(), "PENDING", 30, TimeUnit.MINUTES);

        // 发送消息到“延迟队列”，用于超时未接单自动取消 (例如 30秒后)
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY, order.getId());

        // 加入匹配池 (使用 Redis Set)
        redisTemplate.opsForSet().add(MATCH_POOL_KEY, order.getId().toString());

        return order;
    }

    /**
     * 智能派单任务 (每 5 秒运行一次)
     * 遍历匹配池中的订单，计算评分，指派最佳无人机
     */
    @Scheduled(fixedRate = 5000)
    public void matchOrders() {
        Set<Object> orderIds = redisTemplate.opsForSet().members(MATCH_POOL_KEY);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }

        System.out.println("Starting matchmaking for " + orderIds.size() + " orders...");

        for (Object orderIdObj : orderIds) {
            Long orderId = Long.valueOf(orderIdObj.toString());

            // 获取订单信息 (使用 CacheClient 防穿透/击穿)
            // 这里使用普通 PassThrough 查询即可，如果是极热点单可以使用 LogicalExpire
            Order order = getOrder(orderId);
            if (order == null || !"PENDING".equals(order.getStatus())) {
                // 如果订单已取消或已分配，移除出池子
                redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderIdObj);
                continue;
            }

            // 查找附近无人机 (5km 半径)
            Circle circle = new Circle(new Point(order.getPickupLon(), order.getPickupLat()), new Distance(5.0, Metrics.KILOMETERS));
            RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending();
            GeoResults<RedisGeoCommands.GeoLocation<Object>> results = redisTemplate.opsForGeo().radius(DRONE_GEO_KEY, circle, args);

            if (results == null || results.getContent().isEmpty()) {
                System.out.println("No drones nearby for order " + orderId);
                continue;
            }

            // 计算评分并选出最佳无人机
            Long bestDroneId = null;
            double maxScore = -1.0;

            for (GeoResult<RedisGeoCommands.GeoLocation<Object>> result : results) {
                Long droneId = Long.valueOf(result.getContent().getName().toString());
                double distanceKm = result.getDistance().getValue(); // km

                // 获取无人机电量 (从 DB 查，MVP 简化方案)
                Drone drone = droneMapper.selectById(droneId);
                if (drone == null || !"ONLINE".equals(drone.getStatus())) {
                    continue;
                }

                // 电量硬性校验 (贪心策略过滤)
                if (!isBatterySufficient(order, drone)) {
                    continue;
                }

                // 计算评分: Score = (1 / 距离) * 0.7 + (电量) * 0.3
                // 注意 distance 可能为 0
                double distanceScore = (distanceKm < 0.1) ? 10.0 : (1.0 / distanceKm);
                double batteryScore = drone.getBatteryLevel(); // 0-100

                // 归一化处理 (简单假设)
                double finalScore = (distanceScore * 10) * 0.7 + (batteryScore) * 0.3;

                if (finalScore > maxScore) {
                    maxScore = finalScore;
                    bestDroneId = droneId;
                }
            }

            // 指派订单
            if (bestDroneId != null) {
                boolean success = grabOrder(orderId, bestDroneId);
                if (success) {
                    System.out.println("System matched order " + orderId + " to drone " + bestDroneId + " (Score: " + maxScore + ")");
                    // 从匹配池移除
                    redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderIdObj);
                }
            }
        }
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
     */
    public boolean grabOrder(Long orderId, Long droneId) {
        // --- 1. 智能电池管理校验 (Smart Battery Optimization) ---
        Order order = getOrder(orderId);
        if (order == null) return false;

        Drone drone = droneMapper.selectById(droneId);
        if (drone == null || drone.getBatteryLevel() == null) return false;

        if (!isBatterySufficient(order, drone)) {
            System.out.println("Drone " + droneId + " battery insufficient for order " + orderId);
            return false;
        }

        // --- 2. 状态原子更新 (Redis Lua) ---
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
            // 缓存 无人机-订单 映射关系
            redisTemplate.opsForValue().set("drone:active_order:" + droneId, orderId, 2, TimeUnit.HOURS);

            // 构造消息体，发送到 MQ
            Map<String, Object> msg = Map.of("orderId", orderId, "droneId", droneId);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_GRAB_ROUTING_KEY, msg);

            // Cache Aside: 更新DB前，删除旧缓存 (保证强一致性推荐删除)
            // 虽然这里是预更新状态，但为了严格的 Cache Aside，我们应该删除 Key，等待下次查询回填
            cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

            return true;
        }

        return false;
    }

    /**
     * 完成订单 (幂等性设计)
     */
    @Transactional
    public void completeOrder(Long orderId, String requestId) {
        // 1. 幂等性校验
        Boolean isFirstRequest = redisTemplate.opsForValue().setIfAbsent(
                "idempotency:complete_order:" + requestId, "1", 10, TimeUnit.MINUTES
        );

        if (isFirstRequest == null || !isFirstRequest) {
            System.out.println("Duplicate request detected: " + requestId);
            return;
        }

        // 2. 业务逻辑执行
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found");
        }

        if (!"ASSIGNED".equals(order.getStatus())) {
            if ("COMPLETED".equals(order.getStatus())) {
                return;
            }
            throw new RuntimeException("Order status is not ASSIGNED");
        }

        // 更新订单状态
        order.setStatus("COMPLETED");
        orderMapper.updateById(order);

        // 释放无人机
        Long droneId = order.getDroneId();
        if (droneId != null) {
            droneService.updateStatus(droneId, "ONLINE");
            redisTemplate.delete("drone:active_order:" + droneId);
        }

        // 删除订单缓存 (Cache Aside)
        cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

        System.out.println("Order " + orderId + " completed successfully.");
    }

    /**
     * 判断电池是否足够
     */
    private boolean isBatterySufficient(Order order, Drone drone) {
        double distanceKm = calculateDistance(order.getPickupLat(), order.getPickupLon(), order.getDeliveryLat(), order.getDeliveryLon());
        double speedMs = DRONE_AVG_SPEED_KMH / 3.6;
        double durationSeconds = (distanceKm * 1000) / speedMs;
        double estimatedConsumption = durationSeconds * POWER_CONSUMPTION_PER_SECOND;
        double requiredBattery = estimatedConsumption + 20.0;
        return drone.getBatteryLevel() >= requiredBattery;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 获取订单详情 (使用高级缓存策略)
     * 热点数据查询，使用逻辑过期解决击穿问题
     */
    public Order getOrder(Long orderId) {
        // 使用 CacheClient 的逻辑过期查询方法
        // 假设这里是热点 Key，TTL 设置为 10 分钟 (逻辑过期时间)
        return cacheClient.queryWithLogicalExpire(
                ORDER_CACHE_KEY_PREFIX,
                orderId,
                Order.class,
                id -> orderMapper.selectById(id),
                10L,
                TimeUnit.MINUTES
        );

        // 如果是普通数据，可以用 queryWithPassThrough
        /*
        return cacheClient.queryWithPassThrough(
                ORDER_CACHE_KEY_PREFIX,
                orderId,
                Order.class,
                id -> orderMapper.selectById(id),
                10L,
                TimeUnit.MINUTES
        );
        */
    }

    /**
     * 处理 MQ 抢单消息的具体业务逻辑
     */
    @Transactional
    public void processGrabOrder(Long orderId, Long droneId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PENDING".equals(order.getStatus())) {
            order.setStatus("ASSIGNED");
            order.setDroneId(droneId);
            int rows = orderMapper.updateById(order);
            if (rows > 0) {
                // 落库成功，删除旧缓存 (Cache Aside)
                // 再次删除是为了防止正好在更新DB期间有脏数据写入了缓存
                cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
                System.out.println("Order " + orderId + " grabbed by drone " + droneId + " persisted to DB.");
            } else {
                 System.out.println("Order " + orderId + " grab failed optimistic lock check.");
            }
        } else {
             System.out.println("Order " + orderId + " is not PENDING in DB, skipping grab.");
        }
    }

    /**
     * 处理订单超时逻辑
     */
    @Transactional
    public void processOrderTimeout(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PENDING".equals(order.getStatus())) {
            order.setStatus("CANCELLED");
            orderMapper.updateById(order);

            // 删除缓存
            cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
            redisTemplate.delete(ORDER_STATUS_KEY_PREFIX + orderId);
            redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderId.toString());

            System.out.println("Order " + orderId + " has expired and is cancelled.");
        }
    }
}
