package com.example.skydispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.geo.Point;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NoFlyZoneDto {
    private String name;
    // 多边形顶点列表
    private List<Point> polygon;
}
