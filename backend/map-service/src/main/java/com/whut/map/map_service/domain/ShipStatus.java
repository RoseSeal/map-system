package com.whut.map.map_service.domain;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ShipStatus {
    private String id; // null 表示无法解析 id
    private ShipRole role;
    private double longitude;
    private double latitude;
    private double sog;
    private double cog;
    private Double heading; // 将511表示无法解析航向的情况，用null表示
    private OffsetDateTime msgTime;
    private Double confidence; // null 表示无置信度，0.0表示完全不可信，1.0表示完全可信
}