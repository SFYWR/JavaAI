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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUnitTests {

    @Mock
    private DroneMapper droneMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @InjectMocks
    private DroneService droneService;

    @Test
    void testAdminUpdateStatusSuccess() {
        Drone drone = new Drone();
        drone.setId(1L);
        drone.setStatus("ONLINE");

        when(droneMapper.selectById(1L)).thenReturn(drone);
        // 如果是设置为 OFFLINE，会调用 opsForGeo().remove()，需要 mock
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        droneService.adminUpdateStatus(1L, "OFFLINE");

        verify(droneMapper).updateById(any(Drone.class));
        verify(geoOperations).remove(any(String.class), any(String.class));
    }

    @Test
    void testAdminUpdateStatusBusyFail() {
        Drone drone = new Drone();
        drone.setId(1L);
        drone.setStatus("BUSY");

        when(droneMapper.selectById(1L)).thenReturn(drone);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            droneService.adminUpdateStatus(1L, "OFFLINE");
        });

        Assertions.assertEquals("Cannot modify a busy drone", exception.getMessage());
        verify(droneMapper, never()).updateById(any(Drone.class));
    }

    @Test
    void testAdminUpdateLocationBusyFail() {
        Drone drone = new Drone();
        drone.setId(1L);
        drone.setStatus("BUSY");

        when(droneMapper.selectById(1L)).thenReturn(drone);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            droneService.adminUpdateLocation(1L, 40.0, -74.0);
        });

        Assertions.assertEquals("Cannot modify location of a busy drone", exception.getMessage());
        // Verify geo ops not called (since heartbeat calls it internally)
        // Since heartbeat logic is inside service, we can't easily spy it unless we mock the service itself partially or check redis interaction.
        // But redisTemplate is mocked, so we can verify no interactions with it if exception thrown before heartbeat call.
        verify(redisTemplate, never()).opsForGeo();
    }
}
