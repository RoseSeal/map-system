package com.whut.map.map_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.Map;

/**
 * 前端 2.5D 大屏渲染的核心契约对象。
 * 无论是本船 (Own Ship) 还是目标船 (Target Ship)，都复用此 DTO。
 * 按需填充字段 (Populate fields on demand)。
 */
@Data
@Builder
public class RiskObjectDto {

    // --- Metadata and governance (元数据与管控) ---
    @JsonProperty("risk_object_id")
    private String riskObjectId; // 对应 MMSI

    private long timestamp; // 契约通常期望 Unix 毫秒时间戳，比 Instant 更好处理前端解析

    private Map<String, Object> governance; // 例如: {"mode": "AUTO", "trust_factor": 0.99}

    // --- Core Dynamics (核心动能，所有船都有) ---
    private Map<String, Double> position; // 例如: {"lon": 114.2, "lat": 30.5}

    private Map<String, Double> dynamics; // 例如: {"sog": 12.5, "cog": 24.6, "hdg": 24.6, "rot": 0.0}

    @JsonProperty("platform_health")
    private String platformHealth; // 枚举: NORMAL, DEGRADED, NUC

    // --- Prediction and domain (预测与领域 - 通常本船填充 domain，所有船填充 trajectory) ---
    @JsonProperty("future_trajectory")
    private Object futureTrajectory; // 存放预测轨迹点的集合

    @JsonProperty("safety_domain")
    private Object safetyDomain; // 存放安全领域多边形的坐标点集

    // --- Targets and risk (目标风险 - 通常目标船相对于本船计算后填充) ---
    @JsonProperty("risk_level")
    private String riskLevel; // 枚举: SAFE, CAUTION, WARNING, ALARM

    @JsonProperty("cpa_metrics")
    private Map<String, Double> cpaMetrics; // 例如: {"cpa": 0.11, "tcpa": 4.9}

    @JsonProperty("graphic_cpa_line")
    private Object graphicCpaLine; // 碰撞连线的坐标集

    @JsonProperty("ozt_sector")
    private Object oztSector; // 障碍物区域

    // --- Environment (环境上下文) ---
    @JsonProperty("environment_context")
    private Map<String, Object> environmentContext;
}