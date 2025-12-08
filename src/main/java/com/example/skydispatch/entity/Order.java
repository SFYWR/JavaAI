package com.example.skydispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 订单实体类
 * 对应数据库表 orders
 */
@Data
@TableName("orders")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;

    // 订单描述
    private String description;

    // 取货坐标
    private Double pickupLat;
    private Double pickupLon;

    // 送货坐标
    private Double deliveryLat;
    private Double deliveryLon;

    // 状态: PENDING (待接单), ASSIGNED (已接单), COMPLETED (已完成)
    private String status;

    // 接单的无人机ID
    private Long droneId;

    // 乐观锁版本号
    // MyBatis-Plus 的 OptimisticLockerInnerInterceptor 插件会自动处理此字段
    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
