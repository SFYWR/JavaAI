package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.mapper.DroneMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DroneService {

    @Autowired
    private DroneMapper droneMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DRONE_GEO_KEY = "drones:locations";

    public Drone registerDrone(String serialNumber, String model) {
        Drone drone = new Drone();
        drone.setSerialNumber(serialNumber);
        drone.setModel(model);
        drone.setStatus("OFFLINE");
        drone.setBatteryLevel(100);
        droneMapper.insert(drone);
        return drone;
    }

    public void updateStatus(Long droneId, String status) {
        Drone drone = droneMapper.selectById(droneId);
        if (drone != null) {
            drone.setStatus(status);
            droneMapper.updateById(drone);
            if ("OFFLINE".equals(status)) {
                // Remove from Redis GEO if offline
                redisTemplate.opsForGeo().remove(DRONE_GEO_KEY, droneId.toString());
            }
        }
    }

    public void heartbeat(Long droneId, double lat, double lon) {
        // Update location in Redis GEO
        redisTemplate.opsForGeo().add(DRONE_GEO_KEY, new Point(lon, lat), droneId.toString());

        // Also ensure status is ONLINE if previously OFFLINE?
        // For MVP, assume explicit status updates, but heartbeat keeps it "live".
        // Here we just focus on updating location.
    }
}
