package com.example.skydispatch.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具类
 * 封装：缓存穿透、击穿、雪崩解决方案
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    // 线程池用于异步重建缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 布隆过滤器，用于解决缓存穿透
    // 在实际生产中，需要预热并定期维护
    private RBloomFilter<Long> bloomFilter;

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        // 初始化布隆过滤器，预计插入10万元素，误判率 0.01
        this.bloomFilter = redissonClient.getBloomFilter("cache:bloomfilter");
        this.bloomFilter.tryInit(100000L, 0.01);
    }

    /**
     * 将 ID 添加到布隆过滤器
     */
    public void addToBloomFilter(Long id) {
        bloomFilter.add(id);
    }

    /**
     * 普通设置缓存
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), time, unit);
        } catch (Exception e) {
            log.error("Redis set error", e);
        }
    }

    /**
     * 设置逻辑过期 (解决缓存击穿)
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(redisData));
        } catch (Exception e) {
            log.error("Redis set error", e);
        }
    }

    /**
     * 设置随机过期时间 (解决缓存雪崩)
     * 在基础 TTL 上增加随机波动 (0-10%)
     */
    public void setWithRandomTtl(String key, Object value, Long time, TimeUnit unit) {
        long ttlSeconds = unit.toSeconds(time);
        long jitter = (long) (Math.random() * (ttlSeconds * 0.1));
        set(key, value, ttlSeconds + jitter, TimeUnit.SECONDS);
    }

    /**
     * 解决缓存穿透 (Pass-Through)
     * 流程：查布隆 -> 查缓存 -> 查库 -> 回写缓存(含空值)
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 布隆过滤器校验 (仅针对 Long ID 演示)
        if (id instanceof Long && !bloomFilter.contains((Long) id)) {
            // 不在布隆过滤器中，直接返回 null
            return null;
        }

        // 2. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 3. 判断是否存在
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, type);
            } catch (Exception e) {
                log.error("JSON parse error", e);
            }
        }

        // 判断是否为空值缓存 ("")
        if (json != null) {
            return null;
        }

        // 4. 查询数据库
        R r = dbFallback.apply(id);

        // 5. 不存在，缓存空值 (解决穿透)，TTL 设置较短 (如 2 分钟)
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
            return null;
        }

        // 6. 存在，写入缓存 + 随机 TTL (解决雪崩)
        this.setWithRandomTtl(key, r, time, unit);
        return r;
    }

    /**
     * 解决缓存击穿 (Logical Expiration)
     * 流程：查缓存 -> 检查逻辑过期 -> (未过期)返回 -> (已过期)获取锁 -> 开启线程重建 -> 返回旧数据
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            // 未命中，说明不是热点数据或者尚未预热，降级为查库并设置逻辑过期
            R r = dbFallback.apply(id);
            if (r != null) {
                this.setWithLogicalExpire(key, r, time, unit);
            }
            return r;
        }

        // 2. 反序列化
        RedisData redisData;
        R r;
        try {
            redisData = objectMapper.readValue(json, RedisData.class);
            r = objectMapper.convertValue(redisData.getData(), type);
        } catch (Exception e) {
            log.error("JSON parse error", e);
            return null;
        }

        // 3. 判断是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }

        // 4. 已过期，尝试获取互斥锁
        String lockKey = "lock:" + key;
        RLock lock = redissonClient.getLock(lockKey);

        if (lock.tryLock()) {
            // 5. 获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存 (重置逻辑过期时间)
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    log.error("Cache rebuild error", e);
                } finally {
                    lock.unlock();
                }
            });
        }

        // 6. 无论是否获取锁成功，都先返回旧数据 (保证高可用)
        return r;
    }

    /**
     * 删除缓存 (Cache Aside)
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }
}
