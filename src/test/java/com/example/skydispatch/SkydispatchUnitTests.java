package com.example.skydispatch;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.entity.Order;
import com.example.skydispatch.mapper.DroneMapper;
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
import java.util.function.Function;

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
        // Mock returning an active order ID
        when(valueOperations.get(eq("drone:active_order:1"))).thenReturn(100L);

        droneService.heartbeat(1L, 40.0, -74.0);

        verify(geoOperations).add(any(String.class), any(org.springframework.data.geo.Point.class), any(String.class));
        // Verify WebSocket push
        verify(messagingTemplate).convertAndSend(eq("/topic/orders/100"), any(org.springframework.data.geo.Point.class));
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
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        Order order = orderService.createOrder("Test Order", 0, 0, 1, 1);

        Assertions.assertNotNull(order);
        Assertions.assertEquals(123L, order.getId());
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.delay"), eq(123L));
        // Verify add to match pool
        verify(setOperations).add(eq("order:matchmaking:pool"), eq("123"));
        // Verify CacheClient calls
        verify(cacheClient).setWithRandomTtl(anyString(), any(), anyLong(), any(TimeUnit.class));
        verify(cacheClient).addToBloomFilter(eq(123L));
    }

    @Test
    void testGrabOrderSuccess() {
        // Mock Order and Drone for battery check
        Order order = new Order();
        order.setId(1L);
        order.setPickupLat(40.0); order.setPickupLon(-74.0);
        order.setDeliveryLat(40.0); order.setDeliveryLon(-74.0);

        Drone drone = new Drone();
        drone.setId(100L);
        drone.setBatteryLevel(100);

        // When grabOrder calls getOrder, it now uses cacheClient.queryWithLogicalExpire...
        // But grabOrder implementation calls getOrder() which calls cacheClient.
        // Wait, grabOrder logic in Service calls `getOrder(orderId)`.

        // Mock cacheClient to return order
        when(cacheClient.queryWithLogicalExpire(anyString(), anyLong(), eq(Order.class), any(), anyLong(), any())).thenReturn(order);

        when(droneMapper.selectById(100L)).thenReturn(drone);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        boolean result = orderService.grabOrder(1L, 100L);

        Assertions.assertTrue(result);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
        // Verify cache deleted
        verify(cacheClient).delete(anyString());
    }

    @Test
    void testMatchOrders() {
        // Mock Set operations to return one pending order
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations); // for inner grabOrder

        when(setOperations.members(eq("order:matchmaking:pool"))).thenReturn(new HashSet<>(Collections.singletonList("1")));

        // Mock Order
        Order order = new Order();
        order.setId(1L);
        order.setStatus("PENDING");
        order.setPickupLat(40.0); order.setPickupLon(-74.0);
        order.setDeliveryLat(40.0); order.setDeliveryLon(-74.0);

        // Mock cacheClient return for getOrder
        when(cacheClient.queryWithLogicalExpire(anyString(), anyLong(), eq(Order.class), any(), anyLong(), any())).thenReturn(order);

        // Mock nearby drones (2 drones)
        GeoResult<RedisGeoCommands.GeoLocation<Object>> resultA = new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>("100", new Point(-74.0, 40.0)),
                new Distance(0.1, Metrics.KILOMETERS));

        when(geoOperations.radius(anyString(), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(new GeoResults<>(List.of(resultA)));

        // Mock Drone entities
        Drone droneA = new Drone(); droneA.setId(100L); droneA.setStatus("ONLINE"); droneA.setBatteryLevel(20); // Low

        when(droneMapper.selectById(100L)).thenReturn(droneA);

        // Mock grabOrder success for A
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L); // Success

        orderService.matchOrders();

        // Verify remove from pool
        verify(setOperations).remove(eq("order:matchmaking:pool"), eq("1"));
        // Verify rabbitmq sent (implied by grabOrder success)
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
    }
}
