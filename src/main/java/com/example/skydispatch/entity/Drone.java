package com.example.skydispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("drone")
public class Drone {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String serialNumber;
    private String model;
    private String status; // ONLINE, OFFLINE, BUSY
    private Integer batteryLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
