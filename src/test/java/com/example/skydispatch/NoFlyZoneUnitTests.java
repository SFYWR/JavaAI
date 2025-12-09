package com.example.skydispatch;

import com.example.skydispatch.dto.DroneInstruction;
import com.example.skydispatch.dto.NoFlyZoneDto;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.service.DroneService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoFlyZoneUnitTests {

    @Mock
    private DroneMapper droneMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, Object> valueOperations;

    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private DroneService droneService;

    @Test
    void testHeartbeatNormal() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Mock empty no fly zones
        when(setOperations.members(any(String.class))).thenReturn(Collections.emptySet());

        DroneInstruction instruction = droneService.heartbeat(1L, 40.0, -74.0);

        Assertions.assertEquals("NORMAL", instruction.getType());
    }

    @Test
    void testHeartbeatInNoFlyZone() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Define a square no fly zone around (0,0) from -10 to 10
        NoFlyZoneDto zone = new NoFlyZoneDto("TestZone", Arrays.asList(
                new Point(-10, -10),
                new Point(10, -10),
                new Point(10, 10),
                new Point(-10, 10)
        ));

        when(setOperations.members(any(String.class))).thenReturn(new HashSet<>(Collections.singletonList(zone)));

        // Heartbeat at (0,0) - inside zone
        DroneInstruction instruction = droneService.heartbeat(1L, 0.0, 0.0);

        Assertions.assertEquals("HOVER", instruction.getType());
    }

    @Test
    void testHeartbeatOutsideNoFlyZone() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Define a square no fly zone around (0,0) from -10 to 10
        NoFlyZoneDto zone = new NoFlyZoneDto("TestZone", Arrays.asList(
                new Point(-10, -10),
                new Point(10, -10),
                new Point(10, 10),
                new Point(-10, 10)
        ));

        when(setOperations.members(any(String.class))).thenReturn(new HashSet<>(Collections.singletonList(zone)));

        // Heartbeat at (20,20) - outside zone
        DroneInstruction instruction = droneService.heartbeat(1L, 20.0, 20.0);

        Assertions.assertEquals("NORMAL", instruction.getType());
    }
}
