package com.example.skydispatch.util;

import org.springframework.data.geo.Point;
import java.util.List;

public class GeoUtil {

    /**
     * 判断点是否在多边形内 (射线法)
     * @param point 待判断的点 (x=lon, y=lat)
     * @param polygon 多边形顶点列表
     * @return true 如果在多边形内
     */
    public static boolean isPointInPolygon(Point point, List<Point> polygon) {
        boolean result = false;
        int j = polygon.size() - 1;
        for (int i = 0; i < polygon.size(); i++) {
            Point p1 = polygon.get(i);
            Point p2 = polygon.get(j);

            // 射线法判断逻辑
            if ((p1.getY() > point.getY()) != (p2.getY() > point.getY()) &&
                (point.getX() < (p2.getX() - p1.getX()) * (point.getY() - p1.getY()) / (p2.getY() - p1.getY()) + p1.getX())) {
                result = !result;
            }
            j = i;
        }
        return result;
    }
}
