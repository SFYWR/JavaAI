package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.dto.DroneInstruction;
import com.example.skydispatch.dto.NoFlyZoneDto;
import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.util.GeoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // Redis Key，用于存储所有无人机的地理位置信息 (GEO Hash)
    private static final String DRONE_GEO_KEY = "drones:locations";

    // Redis Key，用于存储禁飞区列表 (Set)
    private static final String NO_FLY_ZONE_KEY = "noflyzones";

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
     * 无人机心跳上报位置 (包含禁飞区检测与实时轨迹推送)
     * 模拟无人机每秒上报 GPS 坐标
     * 使用 Redis GEO (GEOADD) 存储，以便快速计算附近的无人机
     * @param droneId 无人机ID
     * @param lat 纬度
     * @param lon 经度
     * @return DroneInstruction 如果闯入禁飞区，返回 HOVER 或 RETURN 指令；否则返回 NORMAL
     */
    public DroneInstruction heartbeat(Long droneId, double lat, double lon) {
        // 更新 Redis 中的地理位置信息
        // 注意：Redis GEO 接受 (经度, 纬度) 顺序
        Point currentPoint = new Point(lon, lat);
        redisTemplate.opsForGeo().add(DRONE_GEO_KEY, currentPoint, droneId.toString());

        // 实时轨迹推送: 检查当前无人机是否有关联的活跃订单
        Object activeOrderId = redisTemplate.opsForValue().get("drone:active_order:" + droneId);
        if (activeOrderId != null) {
            // 推送到 WebSocket 订阅者: /topic/orders/{orderId}
            messagingTemplate.convertAndSend("/topic/orders/" + activeOrderId, currentPoint);
        }

        // 禁飞区检测
        if (isInsideNoFlyZone(currentPoint)) {
            System.out.println("ALERT: Drone " + droneId + " entered No-Fly Zone at " + lat + "," + lon);
            // 触发紧急悬停
            return new DroneInstruction("HOVER", "WARNING: You have entered a No-Fly Zone! Hovering immediately.");
        }

        return new DroneInstruction("NORMAL", "Status OK");
    }

    // --- 禁飞区相关逻辑 ---

    /**
     * 添加禁飞区
     */
    public void addNoFlyZone(NoFlyZoneDto zone) {
        // 存入 Redis Set
        redisTemplate.opsForSet().add(NO_FLY_ZONE_KEY, zone);
    }

    /**
     * 获取所有禁飞区 (缓存优化：实际生产应使用本地缓存，这里直接查 Redis)
     */
    public List<NoFlyZoneDto> getNoFlyZones() {
        Set<Object> zones = redisTemplate.opsForSet().members(NO_FLY_ZONE_KEY);
        List<NoFlyZoneDto> result = new ArrayList<>();
        if (zones != null) {
            for (Object obj : zones) {
                if (obj instanceof NoFlyZoneDto) {
                    result.add((NoFlyZoneDto) obj);
                }
            }
        }
        return result;
    }

    /**
     * 检查点是否在任何禁飞区内
     */
    private boolean isInsideNoFlyZone(Point point) {
        List<NoFlyZoneDto> zones = getNoFlyZones();
        for (NoFlyZoneDto zone : zones) {
            if (GeoUtil.isPointInPolygon(point, zone.getPolygon())) {
                return true;
            }
        }
        return false;
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
