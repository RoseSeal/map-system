package com.whut.map.map_service.mqtt;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MqttAisDto {

    @JsonProperty("MSGTIME")
    @JsonFormat(pattern = "yyyy-M-d HH:mm:ss")
    private LocalDateTime msgTime;

    @JsonProperty("MMSI")
    private String mmsi;

    @JsonProperty("LON")
    private double longitude;

    @JsonProperty("LAT")
    private double latitude;

    @JsonProperty("SOG")
    private double sog;

    @JsonProperty("COG")
    private double cog;

    @JsonProperty("HEADING")
    private double heading;
}
