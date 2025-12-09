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

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("order:1"))).thenReturn(order);
        when(droneMapper.selectById(100L)).thenReturn(drone);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L);

        boolean result = orderService.grabOrder(1L, 100L);

        Assertions.assertTrue(result);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
    }

    @Test
    void testMatchOrders() {
        // Mock Set operations to return one pending order
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        when(setOperations.members(eq("order:matchmaking:pool"))).thenReturn(new HashSet<>(Collections.singletonList("1")));

        // Mock Order
        Order order = new Order();
        order.setId(1L);
        order.setStatus("PENDING");
        order.setPickupLat(40.0); order.setPickupLon(-74.0);
        order.setDeliveryLat(40.0); order.setDeliveryLon(-74.0);

        when(valueOperations.get(eq("order:1"))).thenReturn(order);

        // Mock nearby drones (2 drones)
        // Drone A: Close but low battery
        // Drone B: Farther but high battery
        GeoResult<RedisGeoCommands.GeoLocation<Object>> resultA = new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>("100", new Point(-74.0, 40.0)),
                new Distance(0.1, Metrics.KILOMETERS));

        GeoResult<RedisGeoCommands.GeoLocation<Object>> resultB = new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>("200", new Point(-74.0, 40.0)),
                new Distance(1.0, Metrics.KILOMETERS));

        when(geoOperations.radius(anyString(), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(new GeoResults<>(List.of(resultA, resultB)));

        // Mock Drone entities
        Drone droneA = new Drone(); droneA.setId(100L); droneA.setStatus("ONLINE"); droneA.setBatteryLevel(20); // Low
        Drone droneB = new Drone(); droneB.setId(200L); droneB.setStatus("ONLINE"); droneB.setBatteryLevel(100); // High

        when(droneMapper.selectById(100L)).thenReturn(droneA);
        when(droneMapper.selectById(200L)).thenReturn(droneB);

        // Mock successful grab for Drone B (System should prefer Drone B due to battery weight or balanced score)
        // Score A: (10 * 10) * 0.7 + 20 * 0.3 = 70 + 6 = 76
        // Score B: (1 * 10) * 0.7 + 100 * 0.3 = 7 + 30 = 37
        // Wait, my formula logic:
        // distanceScore = 1/dist.
        // A: dist=0.1 => 1/0.1 = 10. Final = 10*10 = 100.
        // B: dist=1.0 => 1/1 = 1. Final = 1*10 = 10.
        // Normalized: A_dist=100, B_dist=10.
        // Final A: 100*0.7 + 20*0.3 = 70 + 6 = 76.
        // Final B: 10*0.7 + 100*0.3 = 7 + 30 = 37.
        // A wins.
        // Let's tweak values to make B win to test logic, or just assert A wins.
        // If A has battery 20, is it sufficient?
        // Trip is 0km. 20% is sufficient (> 20.0 maybe? code says >= estimated + 20).
        // If estimated is 0, required is 20. 20 >= 20 is true.
        // So A wins.

        // Mock grabOrder success for A
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenReturn(1L); // Success

        orderService.matchOrders();

        // Verify remove from pool
        verify(setOperations).remove(eq("order:matchmaking:pool"), eq("1"));
        // Verify rabbitmq sent (implied by grabOrder success)
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.grab"), any(Object.class));
    }
}
