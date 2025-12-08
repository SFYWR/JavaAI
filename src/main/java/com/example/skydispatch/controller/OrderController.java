package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Order;
import com.example.skydispatch.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public Order createOrder(@RequestParam String description,
                             @RequestParam double pickupLat, @RequestParam double pickupLon,
                             @RequestParam double deliveryLat, @RequestParam double deliveryLon) {
        return orderService.createOrder(description, pickupLat, pickupLon, deliveryLat, deliveryLon);
    }

    @GetMapping("/nearby-drones")
    public List<String> findNearbyDrones(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "5.0") double radiusKm) {
        return orderService.findNearbyDrones(lat, lon, radiusKm);
    }

    @PostMapping("/{orderId}/grab")
    public String grabOrder(@PathVariable Long orderId, @RequestParam Long droneId) {
        boolean success = orderService.grabOrder(orderId, droneId);
        return success ? "SUCCESS" : "FAILED";
    }

    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }
}
