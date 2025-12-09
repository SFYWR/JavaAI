package com.example.skydispatch;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.mapper.MerchantMapper;
import com.example.skydispatch.mapper.OrderMapper;
import com.example.skydispatch.service.DroneService;
import com.example.skydispatch.service.OrderService;
import com.example.skydispatch.util.CacheClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkydispatchUnitTests {

    @Mock
    private DroneMapper droneMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private MerchantMapper merchantMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private DroneService mockDroneService;

    @Mock
    private CacheClient cacheClient;

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
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.members(anyString())).thenReturn(Collections.emptySet());
        when(valueOperations.get(eq("drone:active_order:1"))).thenReturn(100L);

        droneService.heartbeat(1L, 40.0, -74.0);

        verify(geoOperations).add(any(String.class), any(org.springframework.data.geo.Point.class), any(String.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/orders/100"), any(org.springframework.data.geo.Point.class));
    }

    @Test
    void testCreateOrder() {
        // Mock Merchant
        Merchant merchant = new Merchant();
        merchant.setId(10L);
        merchant.setLat(40.0);
        merchant.setLon(-74.0);
        when(merchantMapper.selectById(10L)).thenReturn(merchant);

        // Mock Order Insert
        doAnswer(invocation -> {
            Order arg = invocation.getArgument(0);
            arg.setId(123L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        // Mock Cache set
        doNothing().when(cacheClient).setWithRandomTtl(anyString(), any(), anyLong(), any(TimeUnit.class));

        Order order = orderService.createOrder("Test Order", 10L, 40.1, -74.1);

        Assertions.assertNotNull(order);
        Assertions.assertEquals(123L, order.getId());
        Assertions.assertEquals("UNPAID", order.getStatus());
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("payment.delay"), eq(123L));
    }

    @Test
    void testPayOrder() {
        Order order = new Order();
        order.setId(123L);
        order.setStatus("UNPAID");

        when(orderMapper.selectById(123L)).thenReturn(order);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        orderService.payOrder(123L);

        Assertions.assertEquals("PENDING", order.getStatus());
        verify(orderMapper).updateById(order);
        verify(setOperations).add(eq("order:matchmaking:pool"), eq("123"));
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.delay"), eq(123L));
    }

    @Test
    void testGrabOrderSuccess() {
        Order order = new Order();
        order.setId(1L);
        order.setPickupLat(40.0); order.setPickupLon(-74.0);
        order.setDeliveryLat(40.0); order.setDeliveryLon(-74.0);

        Drone drone = new Drone();
        drone.setId(100L);
        drone.setBatteryLevel(100);

        when(cacheClient.queryWithLogicalExpire(anyString(), anyLong(), eq(Order.class), any(), anyLong(), any())).thenReturn(order);
        when(droneMapper.selectById(100L)).thenReturn(drone);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Also mock Geo position for drone -> pickup distance check
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.position(anyString(), anyString())).thenReturn(List.of(new Point(-74.0, 40.0)));

        boolean result = orderService.grabOrder(1L, 100L);

        Assertions.assertTrue(result);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
        verify(cacheClient).delete(anyString());
    }
}
