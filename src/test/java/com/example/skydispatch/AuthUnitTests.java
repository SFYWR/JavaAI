package com.example.skydispatch;

import com.example.skydispatch.dto.AuthResponse;
import com.example.skydispatch.entity.User;
import com.example.skydispatch.mapper.UserMapper;
import com.example.skydispatch.service.UserService;
import com.example.skydispatch.util.JwtUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthUnitTests {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private UserService userService;

    @Test
    void testRegister() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("encodedPass");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        User user = userService.register("testuser", "password");

        Assertions.assertEquals("testuser", user.getUsername());
        Assertions.assertEquals("encodedPass", user.getPassword());
    }

    @Test
    void testLogin() {
        when(jwtUtil.generateAccessToken(any())).thenReturn("access_token");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("refresh_token");

        AuthResponse response = userService.login("testuser", "password");

        Assertions.assertEquals("access_token", response.getAccessToken());
        Assertions.assertEquals("refresh_token", response.getRefreshToken());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }
}
