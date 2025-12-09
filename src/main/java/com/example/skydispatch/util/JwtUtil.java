package com.example.skydispatch.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 工具类
 * 用于生成、解析和校验 Token
 */
@Component
public class JwtUtil {

    // 密钥 (至少256位)，实际生产中应存放在配置文件或环境变量中
    private static final String SECRET = "SkyDispatchSecretKeyMustBeVeryLongToSecurelySignTokens1234567890";
    private static final Key KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    // Access Token 有效期 (例如 15分钟)
    private static final long ACCESS_TOKEN_EXPIRATION = 1000 * 60 * 15;

    // Refresh Token 有效期 (例如 7天)
    private static final long REFRESH_TOKEN_EXPIRATION = 1000 * 60 * 60 * 24 * 7;

    /**
     * 生成 Access Token
     */
    public String generateAccessToken(String username) {
        return createToken(new HashMap<>(), username, ACCESS_TOKEN_EXPIRATION);
    }

    /**
     * 生成 Refresh Token
     */
    public String generateRefreshToken(String username) {
        return createToken(new HashMap<>(), username, REFRESH_TOKEN_EXPIRATION);
    }

    private String createToken(Map<String, Object> claims, String subject, long expiration) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }

    /**
     * 从 Token 中提取用户名
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * 判断 Token 是否过期
     */
    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(KEY).build().parseClaimsJws(token).getBody();
    }
}
