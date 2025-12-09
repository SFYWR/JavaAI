package com.example.skydispatch;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.mapper.OrderMapper;
import com.example.skydispatch.service.DroneService;
import com.example.skydispatch.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkydispatchUnitTests {

    @Mock
    private DroneMapper droneMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private DroneService droneService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void testRegisterDrone() {
        when(droneMapper.insert(any(Drone.class))).thenReturn(1);

        Drone drone = droneService.registerDrone("SN100", "ModelY");

        Assertions.assertEquals("SN100", drone.getSerialNumber());
        Assertions.assertEquals("OFFLINE", drone.getStatus());
        verify(droneMapper).insert(any(Drone.class));
    }

    @Test
    void testHeartbeat() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        droneService.heartbeat(1L, 40.0, -74.0);

        verify(geoOperations).add(any(String.class), any(org.springframework.data.geo.Point.class), any(String.class));
    }

    @Test
    void testCreateOrder() {
        // 使用 doAnswer 来模拟数据库插入后的 ID 回填
        doAnswer(invocation -> {
            Order arg = invocation.getArgument(0);
            arg.setId(123L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Order order = orderService.createOrder("Test Order", 0, 0, 1, 1);

        Assertions.assertNotNull(order);
        Assertions.assertEquals(123L, order.getId());
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.delay"), eq(123L));
    }

    @Test
    void testGrabOrderSuccess() {
        // Mock Redis Lua Script execution returning 1 (Success)
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        boolean result = orderService.grabOrder(1L, 100L);

        Assertions.assertTrue(result);
        // Verify message sent to MQ for DB persistence
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
    }

    @Test
    void testGrabOrderFail() {
        // Mock Redis Lua Script execution returning 0 (Fail)
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(0L);

        boolean result = orderService.grabOrder(1L, 100L);

        Assertions.assertFalse(result);
        // Verify NO message sent to MQ
        verify(rabbitTemplate, never()).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
    }
}
