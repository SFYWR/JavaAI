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
    // 匹配池 Key，用于存储待撮合的订单ID
    private static final String MATCH_POOL_KEY = "order:matchmaking:pool";

    // 智能电池管理参数
    private static final double DRONE_AVG_SPEED_KMH = 36.0;
    private static final double POWER_CONSUMPTION_PER_SECOND = 0.05;

    /**
     * 创建订单
     * 1. 验证商户是否存在，设置取货坐标
     * 2. 初始化状态为 UNPAID
     * 3. 发送消息到支付延迟队列 (5分钟)
     * 4. 写入缓存并添加至布隆过滤器
     *
     * @param description 订单描述
     * @param merchantId 商户ID
     * @param deliveryLat 送货纬度
     * @param deliveryLon 送货经度
     * @return 创建成功的订单对象
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

        // Cache Aside 模式：写入数据库后，更新缓存 (使用 CacheClient 封装的随机TTL方法防止雪崩)
        cacheClient.setWithRandomTtl(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10L, TimeUnit.MINUTES);

        // 加入布隆过滤器 (防止缓存穿透)
        cacheClient.addToBloomFilter(order.getId());

        // 发送 5分钟 支付倒计时消息 (TTL Queue -> DLX)
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.PAYMENT_DELAY_ROUTING_KEY, order.getId());

        return order;
    }

    /**
     * 支付订单
     * 1. 更新状态为 PENDING
     * 2. 加入撮合匹配池
     *
     * @param orderId 订单ID
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

        // 更新缓存 (Cache Aside: 删除旧缓存)
        cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

        // 设置 Redis 抢单状态 Key (用于 Lua 原子操作)
        redisTemplate.opsForValue().set(ORDER_STATUS_KEY_PREFIX + orderId, "PENDING", 30, TimeUnit.MINUTES);

        // 发送未接单超时延迟队列 (例如 30秒或更长，防止订单一直无人接单)
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY, orderId);

        // 加入匹配池，开始系统撮合
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

            // 获取订单信息 (使用 CacheClient 防穿透/击穿)
            Order order = getOrder(orderId);
            if (order == null || !"PENDING".equals(order.getStatus())) {
                // 如果订单已取消或已分配，移除出池子
                redisTemplate.opsForSet().remove(MATCH_POOL_KEY, orderIdObj);
                continue;
            }

            // 查找附近无人机 (5km 半径，基于订单取货点)
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
                double distanceToMerchantKm = result.getDistance().getValue(); // Drone -> Merchant 距离

                // 获取无人机电量 (从 DB 查，MVP 简化方案)
                Drone drone = droneMapper.selectById(droneId);
                if (drone == null || !"ONLINE".equals(drone.getStatus())) {
                    continue;
                }

                // 电量硬性校验 (贪心策略过滤，包含两段路程)
                if (!isBatterySufficient(order, drone, distanceToMerchantKm)) {
                    continue;
                }

                // 评分模型:
                // 1. 距离得分: 距离越近分越高 (Drone -> Merchant)
                // 2. 电量得分: 电量越高分越高
                double distanceScore = (distanceToMerchantKm < 0.1) ? 10.0 : (1.0 / distanceToMerchantKm);
                double batteryScore = drone.getBatteryLevel(); // 0-100

                // 归一化加权求和
                double finalScore = (distanceScore * 10) * 0.7 + (batteryScore) * 0.3;

                if (finalScore > maxScore) {
                    maxScore = finalScore;
                    bestDroneId = droneId;
                }
            }

            // 指派订单
            if (bestDroneId != null) {
                // 使用原有的原子抢单逻辑来执行指派 (保证状态一致性)
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
     * 查找附近的无人机 (API 用)
     * @param lat 中心纬度
     * @param lon 中心经度
     * @param radiusKm 搜索半径
     * @return 附近的无人机ID列表
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
     * 高并发抢单/指派接口 (优化版)
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
        Order order = getOrder(orderId);
        if (order == null) return false;

        Drone drone = droneMapper.selectById(droneId);
        if (drone == null || drone.getBatteryLevel() == null) return false;

        // 获取 Drone 实时坐标 (Redis) 用于计算距离
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

        // --- 2. 状态原子更新 (Redis Lua) ---
        // Lua 脚本：检查状态是否为 PENDING，如果是，则更新为 ASSIGNED 并返回 1
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
            Map<String, Object> msg = Map.of("orderId", orderId, "droneId", droneId);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_GRAB_ROUTING_KEY, msg);

            // Cache Aside: 更新DB前，删除旧缓存 (保证强一致性推荐删除)
            cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

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
            System.out.println("Duplicate request detected: " + requestId);
            return;
        }

        // 2. 业务逻辑执行
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found");
        }

        if (!"ASSIGNED".equals(order.getStatus())) {
            // 如果已经是 COMPLETED，可能是上次请求数据库成功但缓存失败导致的，或者是并发问题
            if ("COMPLETED".equals(order.getStatus())) {
                return;
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

        // 删除订单缓存 (Cache Aside)
        cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);

        System.out.println("Order " + orderId + " completed successfully.");
    }

    /**
     * 判断电池是否足够 (双段距离：Drone -> Merchant + Merchant -> User)
     * 逻辑：(总距离 / 平均速度) * 耗电因子 < 当前电量
     */
    private boolean isBatterySufficient(Order order, Drone drone, double distanceToMerchantKm) {
        // Segment 1: Drone -> Merchant (传入参数)
        // Segment 2: Merchant -> User (Pickup -> Delivery)
        double distanceMerchantToUserKm = calculateDistance(order.getPickupLat(), order.getPickupLon(), order.getDeliveryLat(), order.getDeliveryLon());

        double totalDistanceKm = distanceToMerchantKm + distanceMerchantToUserKm;

        double speedMs = DRONE_AVG_SPEED_KMH / 3.6;
        double durationSeconds = (totalDistanceKm * 1000) / speedMs;
        double estimatedConsumption = durationSeconds * POWER_CONSUMPTION_PER_SECOND;

        // 预留 20% 安全电量
        double requiredBattery = estimatedConsumption + 20.0;

        return drone.getBatteryLevel() >= requiredBattery;
    }

    // 简易 Haversine 距离计算 (km)
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
     * @param orderId 订单ID
     * @return 订单详情
     */
    public Order getOrder(Long orderId) {
        // 使用 CacheClient 的逻辑过期查询方法
        return cacheClient.queryWithLogicalExpire(
                ORDER_CACHE_KEY_PREFIX,
                orderId,
                Order.class,
                id -> orderMapper.selectById(id),
                10L,
                TimeUnit.MINUTES
        );
    }

    /**
     * 处理 MQ 抢单消息的具体业务逻辑 (供 Listener 调用)
     * 使用数据库乐观锁作为最终一致性保障
     * @param orderId 订单ID
     * @param droneId 无人机ID
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
     * 处理订单超时逻辑 (处理未接单超时和支付超时)
     * 根据当前状态决定处理逻辑
     * @param orderId 订单ID
     */
    @Transactional
    public void processOrderTimeout(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null) {
            // 支付超时: UNPAID -> CANCELLED
            if ("UNPAID".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderMapper.updateById(order);
                cacheClient.delete(ORDER_CACHE_KEY_PREFIX + orderId);
                System.out.println("Order " + orderId + " payment timeout, cancelled.");
            }
            // 匹配/接单超时: PENDING -> CANCELLED
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
