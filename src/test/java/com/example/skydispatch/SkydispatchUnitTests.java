package com.example.skydispatch;

import com.example.skydispatch.entity.Drone;
import com.example.skydispatch.mapper.DroneMapper;
import com.example.skydispatch.service.DroneService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkydispatchUnitTests {

    @Mock
    private DroneMapper droneMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @InjectMocks
    private DroneService droneService;

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
}
