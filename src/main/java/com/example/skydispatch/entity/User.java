package com.example.skydispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体类
 * 对应数据库表 users
 */
@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户名
    private String username;

    // 密码 (加密存储)
    private String password;

    // 角色 (ROLE_USER, ROLE_ADMIN)
    private String role;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
