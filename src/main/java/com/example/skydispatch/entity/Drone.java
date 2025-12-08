package com.example.skydispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 无人机实体类
 * 对应数据库表 drone
 */
@Data
@TableName("drone")
public class Drone {
    @TableId(type = IdType.AUTO)
    private Long id;

    // 序列号，唯一标识
    private String serialNumber;

    // 型号
    private String model;

    // 状态: ONLINE (在线), OFFLINE (离线), BUSY (忙碌/配送中)
    private String status;

    // 电池电量 (0-100)
    private Integer batteryLevel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
