package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.mapper.DroneMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 无人机核心服务类
 * 处理无人机注册、状态更新及地理位置上报
 */
@Service
public class DroneService {

    @Autowired
    private DroneMapper droneMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Redis Key，用于存储所有无人机的地理位置信息 (GEO Hash)
    private static final String DRONE_GEO_KEY = "drones:locations";

    /**
     * 注册新无人机
     * @param serialNumber 序列号
     * @param model 型号
     * @return 注册后的无人机实体
     */
    public Drone registerDrone(String serialNumber, String model) {
        Drone drone = new Drone();
        drone.setSerialNumber(serialNumber);
        drone.setModel(model);
        drone.setStatus("OFFLINE"); // 默认为离线状态
        drone.setBatteryLevel(100);
        droneMapper.insert(drone);
        return drone;
    }

    /**
     * 更新无人机状态
     * @param droneId 无人机ID
     * @param status 新状态 (ONLINE, OFFLINE, BUSY)
     */
    public void updateStatus(Long droneId, String status) {
        Drone drone = droneMapper.selectById(droneId);
        if (drone != null) {
            drone.setStatus(status);
            droneMapper.updateById(drone);
            // 如果无人机下线，从 Redis GEO 中移除其位置信息
            if ("OFFLINE".equals(status)) {
                redisTemplate.opsForGeo().remove(DRONE_GEO_KEY, droneId.toString());
            }
        }
    }

    /**
     * 无人机心跳上报位置
     * 模拟无人机每秒上报 GPS 坐标
     * 使用 Redis GEO (GEOADD) 存储，以便快速计算附近的无人机
     * @param droneId 无人机ID
     * @param lat 纬度
     * @param lon 经度
     */
    public void heartbeat(Long droneId, double lat, double lon) {
        // 更新 Redis 中的地理位置信息
        // 注意：Redis GEO 接受 (经度, 纬度) 顺序
        redisTemplate.opsForGeo().add(DRONE_GEO_KEY, new Point(lon, lat), droneId.toString());

        // 在实际生产中，可能还需要更新数据库中的最后位置，或检查状态是否需要变更为 ONLINE
    }

    // --- 管理员专用方法 ---

    /**
     * 管理员添加新无人机
     */
    public Drone adminAddDrone(String serialNumber, String model) {
        return registerDrone(serialNumber, model);
    }

    /**
     * 管理员修改无人机状态
     * 约束：不能修改正在忙碌(BUSY)的无人机
     */
    public void adminUpdateStatus(Long droneId, String status) {
        Drone drone = droneMapper.selectById(droneId);
        if (drone == null) {
            throw new RuntimeException("Drone not found");
        }
        if ("BUSY".equals(drone.getStatus())) {
            throw new RuntimeException("Cannot modify a busy drone");
        }
        updateStatus(droneId, status);
    }

    /**
     * 管理员修改无人机位置
     * 约束：不能修改正在忙碌(BUSY)的无人机
     */
    public void adminUpdateLocation(Long droneId, double lat, double lon) {
        Drone drone = droneMapper.selectById(droneId);
        if (drone == null) {
            throw new RuntimeException("Drone not found");
        }
        if ("BUSY".equals(drone.getStatus())) {
            throw new RuntimeException("Cannot modify location of a busy drone");
        }
        // 更新 Redis GEO
        heartbeat(droneId, lat, lon);
    }
}
