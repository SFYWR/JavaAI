package com.example.skydispatch.controller;

import com.example.skydispatch.dto.AuthRequest;
import com.example.skydispatch.dto.AuthResponse;
import com.example.skydispatch.entity.User;
import com.example.skydispatch.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(userService.register(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(userService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody String refreshToken) {
        // 简单处理，如果是 JSON 包含 key 则需解析，这里假设直接传 token 字符串或 "refreshToken": "..."
        // 为了简化，假设客户端传纯文本或 JSON key "refreshToken"
        // 实际开发建议用 DTO 接收
        // 这里偷懒处理一下，如果包含 quotes 就去掉
        String token = refreshToken.replaceAll("\"", "").replaceAll("refreshToken:", "").trim();
        if (token.startsWith("{")) {
             // 简单的 JSON 解析 hack，实际应使用 @RequestBody RefreshTokenRequest dto
             // 假设格式 {"refreshToken": "xyz"}
             token = token.substring(token.indexOf(":") + 1, token.length() - 1).trim().replaceAll("\"", "");
        }

        return ResponseEntity.ok(userService.refreshToken(token));
    }
}
