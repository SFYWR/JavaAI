package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.dto.AuthResponse;
import com.example.skydispatch.entity.User;
import com.example.skydispatch.mapper.UserMapper;
import com.example.skydispatch.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthenticationManager authenticationManager;

    /**
     * 用户注册
     */
    public User register(String username, String password) {
        if (userMapper.selectCount(new QueryWrapper<User>().eq("username", username)) > 0) {
            throw new RuntimeException("Username already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("ROLE_USER");
        userMapper.insert(user);
        return user;
    }

    /**
     * 用户登录
     * 返回 Access Token 和 Refresh Token
     */
    public AuthResponse login(String username, String password) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
        );
        String accessToken = jwtUtil.generateAccessToken(username);
        String refreshToken = jwtUtil.generateRefreshToken(username);
        return new AuthResponse(accessToken, refreshToken);
    }

    /**
     * 刷新 Token (自动续期逻辑)
     */
    public AuthResponse refreshToken(String refreshToken) {
        String username = jwtUtil.extractUsername(refreshToken);
        if (username != null && jwtUtil.validateToken(refreshToken, username)) {
            // 生成新的 Access Token
            String newAccessToken = jwtUtil.generateAccessToken(username);
            // Refresh Token 通常也可以选择滚动更新，或者保持不变直到过期
            // 这里简单策略：只更新 Access Token，返回原 Refresh Token (或者也可以生成新的)
            // 为了更安全的续期，建议也生成新的 Refresh Token (Sliding Window)
            String newRefreshToken = jwtUtil.generateRefreshToken(username);
            return new AuthResponse(newAccessToken, newRefreshToken);
        }
        throw new RuntimeException("Invalid Refresh Token");
    }
}
