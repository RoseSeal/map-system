package com.whut.map.map_service.shared.domain;

public enum QualityFlag {
    // 5A protocol-level flags
    MISSING_HEADING,
    SPEED_OUT_OF_RANGE,
    POSITION_OUT_OF_RANGE,
    MISSING_TIMESTAMP,
    
    // 5B: 运动学层
    POSITION_JUMP,   // 相邻帧位置跳变
    SOG_JUMP,        // 航速突变
    COG_JUMP         // 航向突变
}