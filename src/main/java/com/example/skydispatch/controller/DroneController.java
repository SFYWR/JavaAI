package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.service.DroneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drones")
public class DroneController {

    @Autowired
    private DroneService droneService;

    @PostMapping("/register")
    public Drone register(@RequestParam String serialNumber, @RequestParam String model) {
        return droneService.registerDrone(serialNumber, model);
    }

    @PostMapping("/{id}/status")
    public void updateStatus(@PathVariable Long id, @RequestParam String status) {
        droneService.updateStatus(id, status);
    }

    @PostMapping("/{id}/heartbeat")
    public void heartbeat(@PathVariable Long id, @RequestParam double lat, @RequestParam double lon) {
        droneService.heartbeat(id, lat, lon);
    }
}
