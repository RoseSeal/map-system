package com.whut.map.map_service.domain;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AisMessage {
    private OffsetDateTime msgTime;
    private int mmsi;
    private double longitude;
    private double latitude;
    private double sog;
    private double cog;
    private Double heading;
    private ShipRole role;
}