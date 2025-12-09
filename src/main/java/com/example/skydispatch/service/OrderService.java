package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.skydispatch.config.RabbitConfig;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.mapper.MerchantMapper;
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
    private MerchantMapper merchantMapper;

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
    private static final String MATCH_POOL_KEY = "order:matchmaking:pool";

    private static final double DRONE_AVG_SPEED_KMH = 36.0;
    private static final double POWER_CONSUMPTION_PER_SECOND = 0.05;

    /**
     * 创建订单
     * 1. 验证商户是否存在，设置取货坐标
     * 2. 初始化状态为 UNPAID
     * 3. 发送消息到支付延迟队列 (5分钟)
     */
    public Order createOrder(String description, Long merchantId, double deliveryLat, double deliveryLon) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new RuntimeException("Merchant not found");
        }

        Order order = new Order();
        order.setDescription(description);
        order.setMerchantId(merchantId);
        order.setPickupLat(merchant.getLat());
        order.setPickupLon(merchant.getLon());
        order.setDeliveryLat(deliveryLat);
        order.setDeliveryLon(deliveryLon);
        order.setStatus("UNPAID");
        orderMapper.insert(order);

        // 更新缓存
        cacheClient.setWithRandomTtl(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10L, TimeUnit.MINUTES);
        cacheClient.addToBloomFilter(order.getId());

        // 发送 5分钟 支付倒计时消息
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.PAYMENT_DELAY_ROUTING_KEY, order.getId());

        return order;
    }

    /**
     * 支付订单
     * 1. 更新状态为 PENDING
     * 2. 加入匹配池
     */
    @Transactional
    public void payOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found");
        }
        if (!"UNPAID".equals(order.getStatus())) {
            throw new RuntimeException("Order is not in UNPAID status");
        }

        order.setStatus("PENDING");
        orderMapper.updateById(order);

        // 更新缓存
        cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

        // 设置抢单状态 Key
        redisTemplate.opsForValue().set(ORDER_STATUS_KEY_PREFIX + orderId, "PENDING", 30, TimeUnit.MINUTES);

        // 发送未接单超时延迟队列 (例如 30秒或更长)
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY, orderId);

        // 加入匹配池，开始撮合
        redisTemplate.opsForSet().add(MATCH_POOL_KEY, orderId.toString());
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

            Order order = getOrder(orderId);
            if (order == null || !"PENDING".equals(order.getStatus())) {
                redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderIdObj);
                continue;
            }

            // 查找附近无人机 (基于商户位置/Pickup Location)
            Circle circle = new Circle(new Point(order.getPickupLon(), order.getPickupLat()), new Distance(5.0, Metrics.KILOMETERS));
            RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending();
            GeoResults<RedisGeoCommands.GeoLocation<Object>> results = redisTemplate.opsForGeo().radius(DRONE_GEO_KEY, circle, args);

            if (results == null || results.getContent().isEmpty()) {
                continue;
            }

            Long bestDroneId = null;
            double maxScore = -1.0;

            for (GeoResult<RedisGeoCommands.GeoLocation<Object>> result : results) {
                Long droneId = Long.valueOf(result.getContent().getName().toString());
                double distanceToMerchantKm = result.getDistance().getValue(); // Drone -> Merchant

                Drone drone = droneMapper.selectById(droneId);
                if (drone == null || !"ONLINE".equals(drone.getStatus())) {
                    continue;
                }

                // 传入当前 Drone -> Merchant 的距离用于电量校验
                if (!isBatterySufficient(order, drone, distanceToMerchantKm)) {
                    continue;
                }

                // 评分模型: 距离越近越好 (Drone -> Merchant)，电量越高越好
                double distanceScore = (distanceToMerchantKm < 0.1) ? 10.0 : (1.0 / distanceToMerchantKm);
                double batteryScore = drone.getBatteryLevel();

                double finalScore = (distanceScore * 10) * 0.7 + (batteryScore) * 0.3;

                if (finalScore > maxScore) {
                    maxScore = finalScore;
                    bestDroneId = droneId;
                }
            }

            if (bestDroneId != null) {
                boolean success = grabOrder(orderId, bestDroneId);
                if (success) {
                    System.out.println("System matched order " + orderId + " to drone " + bestDroneId + " (Score: " + maxScore + ")");
                    redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderIdObj);
                }
            }
        }
    }

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

    public boolean grabOrder(Long orderId, Long droneId) {
        Order order = getOrder(orderId);
        if (order == null) return false;

        Drone drone = droneMapper.selectById(droneId);
        if (drone == null || drone.getBatteryLevel() == null) return false;

        // 在 grabOrder 中，如果没有传入距离，需要计算 Drone -> Merchant
        // 获取 Drone 坐标 (Redis)
        List<Point> points = redisTemplate.opsForGeo().position(DRONE_GEO_KEY, droneId.toString());
        double distanceToMerchantKm = 0.0;
        if (points != null && !points.isEmpty()) {
            Point droneLoc = points.get(0);
            distanceToMerchantKm = calculateDistance(droneLoc.getY(), droneLoc.getX(), order.getPickupLat(), order.getPickupLon());
        }

        if (!isBatterySufficient(order, drone, distanceToMerchantKm)) {
            System.out.println("Drone " + droneId + " battery insufficient for order " + orderId);
            return false;
        }

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
            redisTemplate.opsForValue().set("drone:active_order:" + droneId, orderId, 2, TimeUnit.HOURS);
            Map<String, Object> msg = Map.of("orderId", orderId, "droneId", droneId);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_GRAB_ROUTING_KEY, msg);
            cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
            return true;
        }

        return false;
    }

    @Transactional
    public void completeOrder(Long orderId, String requestId) {
        Boolean isFirstRequest = redisTemplate.opsForValue().setIfAbsent(
                "idempotency:complete_order:" + requestId, "1", 10, TimeUnit.MINUTES
        );

        if (isFirstRequest == null || !isFirstRequest) {
            return;
        }

        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("Order not found");

        if (!"ASSIGNED".equals(order.getStatus())) {
            if ("COMPLETED".equals(order.getStatus())) return;
            throw new RuntimeException("Order status is not ASSIGNED");
        }

        order.setStatus("COMPLETED");
        orderMapper.updateById(order);

        Long droneId = order.getDroneId();
        if (droneId != null) {
            droneService.updateStatus(droneId, "ONLINE");
            redisTemplate.delete("drone:active_order:" + droneId);
        }
        cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
    }

    /**
     * 判断电池是否足够 (双段距离：Drone -> Merchant + Merchant -> User)
     */
    private boolean isBatterySufficient(Order order, Drone drone, double distanceToMerchantKm) {
        // Segment 1: Drone -> Merchant (传入参数)
        // Segment 2: Merchant -> User (Pickup -> Delivery)
        double distanceMerchantToUserKm = calculateDistance(order.getPickupLat(), order.getPickupLon(), order.getDeliveryLat(), order.getDeliveryLon());

        double totalDistanceKm = distanceToMerchantKm + distanceMerchantToUserKm;

        double speedMs = DRONE_AVG_SPEED_KMH / 3.6;
        double durationSeconds = (totalDistanceKm * 1000) / speedMs;
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

    public Order getOrder(Long orderId) {
        return cacheClient.queryWithLogicalExpire(
                ORDER_CACHE_KEY_PREFIX,
                orderId,
                Order.class,
                id -> orderMapper.selectById(id),
                10L,
                TimeUnit.MINUTES
        );
    }

    @Transactional
    public void processGrabOrder(Long orderId, Long droneId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PENDING".equals(order.getStatus())) {
            order.setStatus("ASSIGNED");
            order.setDroneId(droneId);
            int rows = orderMapper.updateById(order);
            if (rows > 0) {
                cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
            }
        }
    }

    /**
     * 处理订单超时逻辑 (处理未接单超时和支付超时)
     * 根据当前状态决定处理逻辑
     */
    @Transactional
    public void processOrderTimeout(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null) {
            // 支付超时
            if ("UNPAID".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderMapper.updateById(order);
                cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
                System.out.println("Order " + orderId + " payment timeout, cancelled.");
            }
            // 匹配/接单超时
            else if ("PENDING".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderMapper.updateById(order);
                cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
                redisTemplate.delete(ORDER_STATUS_KEY_PREFIX + orderId);
                redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderId.toString());
                System.out.println("Order " + orderId + " matching timeout, cancelled.");
            }
        }
    }
}
