package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
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

    private static final String DRONE_GEO_KEY = "drones:locations";
    private static final String ORDER_CACHE_KEY_PREFIX = "order:";

    /**
     * 创建订单
     * 将订单数据存入 MySQL，并预热 Redis 缓存
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
        // 这里的策略是立即存入缓存，以应对即时的热点查询
        redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10, TimeUnit.MINUTES);

        return order;
    }

    /**
     * 查找附近的无人机
     * 核心逻辑：使用 Redis GEORADIUS 命令查找指定半径内的空闲无人机
     * @param lat 订单/位置纬度
     * @param lon 订单/位置经度
     * @param radiusKm 搜索半径 (千米)
     * @return 附近的无人机ID列表
     */
    public List<String> findNearbyDrones(double lat, double lon, double radiusKm) {
        // 创建圆心和半径
        Circle circle = new Circle(new Point(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS));

        // Redis GEO 查询参数：包含距离、按距离升序排列
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending();

        // 执行查询
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
     * 高并发抢单接口
     * 核心难点：防止超卖（多个无人机抢到同一个订单）
     * 解决方案：MySQL 乐观锁 (Optimistic Locking)
     * @param orderId 订单ID
     * @param droneId 抢单的无人机ID
     * @return true 如果抢单成功，false 如果失败
     */
    @Transactional
    public boolean grabOrder(Long orderId, Long droneId) {
        // 1. 查询订单状态
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        // 只有 PENDING 状态的订单才能被抢
        if (!"PENDING".equals(order.getStatus())) {
            return false;
        }

        // 2. 尝试更新订单状态
        // 乐观锁原理：MyBatis-Plus 会自动检查 version 字段
        // SQL 类似于: UPDATE orders SET status='ASSIGNED', drone_id=?, version=version+1 WHERE id=? AND version=?
        order.setStatus("ASSIGNED");
        order.setDroneId(droneId);

        // updateById 返回受影响的行数
        // 如果 version 在查询后被其他线程修改，则更新失败，返回 0
        int rows = orderMapper.updateById(order);

        if (rows > 0) {
            // 抢单成功，更新 Redis 缓存
            redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, order, 10, TimeUnit.MINUTES);
            return true;
        }

        // 抢单失败（已被其他人抢先修改了版本号）
        return false;
    }

    /**
     * 获取订单详情 (Cache-Aside Pattern)
     * 先查 Redis，缓存未命中则查数据库并回填
     */
    public Order getOrder(Long orderId) {
        String key = ORDER_CACHE_KEY_PREFIX + orderId;
        // 查询缓存
        Order order = (Order) redisTemplate.opsForValue().get(key);
        if (order == null) {
            // 缓存未命中，查询数据库
            order = orderMapper.selectById(orderId);
            if (order != null) {
                // 回填缓存，设置过期时间防止内存泄漏
                redisTemplate.opsForValue().set(key, order, 10, TimeUnit.MINUTES);
            }
        }
        return order;
    }
}
