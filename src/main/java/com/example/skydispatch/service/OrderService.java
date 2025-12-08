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

@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DRONE_GEO_KEY = "drones:locations";
    private static final String ORDER_CACHE_KEY_PREFIX = "order:";

    public Order createOrder(String description, double pickupLat, double pickupLon, double deliveryLat, double deliveryLon) {
        Order order = new Order();
        order.setDescription(description);
        order.setPickupLat(pickupLat);
        order.setPickupLon(pickupLon);
        order.setDeliveryLat(deliveryLat);
        order.setDeliveryLon(deliveryLon);
        order.setStatus("PENDING");
        orderMapper.insert(order);

        // Cache Aside: We could put it in cache, but normally we cache on read.
        // Let's populate cache for "hot" access immediately for demo.
        redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + order.getId(), order, 10, TimeUnit.MINUTES);

        return order;
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

    // High concurrency grab order
    // Return true if success, false if failed (already grabbed or version mismatch)
    @Transactional
    public boolean grabOrder(Long orderId, Long droneId) {
        // 1. Check Redis Cache first (optimization)
        // For strict consistency in "grabbing", we usually go to DB with optimistic lock.
        // But to prevent hammering DB, we can use a Redis key as a lock or flag.
        // Here, the requirement asks for "Redis Atomic or MySQL Optimistic Lock".
        // I will use MySQL Optimistic Lock as it's cleaner for data consistency in this MVP.

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        if (!"PENDING".equals(order.getStatus())) {
            return false;
        }

        // Optimistic Locking via Version
        order.setStatus("ASSIGNED");
        order.setDroneId(droneId);

        // Update returns number of affected rows. If 0, it means version changed (someone else grabbed it).
        int rows = orderMapper.updateById(order);

        if (rows > 0) {
            // Update Cache
            redisTemplate.opsForValue().set(ORDER_CACHE_KEY_PREFIX + orderId, order, 10, TimeUnit.MINUTES);
            return true;
        }

        return false;
    }

    public Order getOrder(Long orderId) {
        // Cache Aside
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
}
