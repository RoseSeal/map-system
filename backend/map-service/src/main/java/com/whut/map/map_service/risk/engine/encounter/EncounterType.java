package com.whut.map.map_service.risk.engine.encounter;

/**
 * Encounter situation types between two ships.
 */
public enum EncounterType {
    HEAD_ON,    // 对遇：两船相向，互视对方为船头
    OVERTAKING, // 追越：目标在本船船尾弧内且航向近似同向
    CROSSING,   // 交叉：其余有效的相遇几何态势
    UNDEFINED   // 数据不足（COG 无效等），无法判定
}
