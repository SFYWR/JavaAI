package com.example.skydispatch.controller;

import com.example.skydispatch.dto.NoFlyZoneDto;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.service.DroneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private DroneService droneService;

    // --- 无人机管理 ---

    /**
     * 添加新无人机
     */
    @PostMapping("/drones")
    public ResponseEntity<Drone> addDrone(@RequestParam String serialNumber, @RequestParam String model) {
        return ResponseEntity.ok(droneService.adminAddDrone(serialNumber, model));
    }

    /**
     * 修改无人机状态
     */
    @PutMapping("/drones/{id}/status")
    public ResponseEntity<String> updateStatus(@PathVariable Long id, @RequestParam String status) {
        try {
            droneService.adminUpdateStatus(id, status);
            return ResponseEntity.ok("Status updated successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 修改无人机位置
     */
    @PutMapping("/drones/{id}/location")
    public ResponseEntity<String> updateLocation(@PathVariable Long id, @RequestParam double lat, @RequestParam double lon) {
        try {
            droneService.adminUpdateLocation(id, lat, lon);
            return ResponseEntity.ok("Location updated successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // --- 禁飞区管理 ---

    /**
     * 添加禁飞区
     * 接收 JSON 格式的 NoFlyZoneDto
     */
    @PostMapping("/noflyzones")
    public ResponseEntity<String> addNoFlyZone(@RequestBody NoFlyZoneDto noFlyZone) {
        droneService.addNoFlyZone(noFlyZone);
        return ResponseEntity.ok("No-Fly Zone added successfully");
    }
}
