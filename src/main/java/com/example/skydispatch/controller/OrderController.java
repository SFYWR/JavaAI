package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Order;
import com.example.skydispatch.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单管理接口
 * 提供创建订单、查询附近无人机、抢单等功能
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 发布新订单
     */
    @PostMapping
    public Order createOrder(@RequestParam String description,
                             @RequestParam double pickupLat, @RequestParam double pickupLon,
                             @RequestParam double deliveryLat, @RequestParam double deliveryLon) {
        return orderService.createOrder(description, pickupLat, pickupLon, deliveryLat, deliveryLon);
    }

    /**
     * 查询附近的无人机
     * @param lat 中心纬度
     * @param lon 中心经度
     * @param radiusKm 搜索半径（公里），默认5.0
     * @return 附近的无人机ID列表
     */
    @GetMapping("/nearby-drones")
    public List<String> findNearbyDrones(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "5.0") double radiusKm) {
        return orderService.findNearbyDrones(lat, lon, radiusKm);
    }

    /**
     * 无人机抢单
     * @param orderId 订单ID
     * @param droneId 抢单无人机ID
     * @return SUCCESS 或 FAILED
     */
    @PostMapping("/{orderId}/grab")
    public String grabOrder(@PathVariable Long orderId, @RequestParam Long droneId) {
        boolean success = orderService.grabOrder(orderId, droneId);
        return success ? "SUCCESS" : "FAILED";
    }

    /**
     * 完成订单 (幂等性接口)
     * @param orderId 订单ID
     * @param requestId 请求唯一标识，用于幂等校验
     */
    @PostMapping("/{orderId}/complete")
    public ResponseEntity<String> completeOrder(@PathVariable Long orderId, @RequestParam String requestId) {
        try {
            orderService.completeOrder(orderId, requestId);
            return ResponseEntity.ok("Order completed successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 获取订单详情
     */
    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }
}
