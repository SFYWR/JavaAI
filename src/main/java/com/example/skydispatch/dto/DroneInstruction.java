package com.example.skydispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DroneInstruction {
    // 指令类型: NORMAL, HOVER (悬停), RETURN (返航)
    private String type;
    private String message;
}
