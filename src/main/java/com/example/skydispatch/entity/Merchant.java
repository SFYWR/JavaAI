package com.example.skydispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商户实体类
 * 对应数据库表 merchant
 */
@Data
@TableName("merchant")
public class Merchant {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    // 商户坐标 (取餐点)
    private Double lat;
    private Double lon;

    private String address;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
