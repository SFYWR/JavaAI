package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.service.DroneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 无人机管理接口
 * 提供无人机注册、状态变更、心跳上报功能
 */
@RestController
@RequestMapping("/api/drones")
public class DroneController {

    @Autowired
    private DroneService droneService;

    /**
     * 注册无人机
     */
    @PostMapping("/register")
    public Drone register(@RequestParam String serialNumber, @RequestParam String model) {
        return droneService.registerDrone(serialNumber, model);
    }

    /**
     * 更新无人机状态
     * @param id 无人机ID
     * @param status 状态 (ONLINE, OFFLINE, BUSY)
     */
    @PostMapping("/{id}/status")
    public void updateStatus(@PathVariable Long id, @RequestParam String status) {
        droneService.updateStatus(id, status);
    }

    /**
     * 接收无人机心跳（GPS坐标）
     * @param id 无人机ID
     * @param lat 纬度
     * @param lon 经度
     */
    @PostMapping("/{id}/heartbeat")
    public void heartbeat(@PathVariable Long id, @RequestParam double lat, @RequestParam double lon) {
        droneService.heartbeat(id, lat, lon);
    }
}
