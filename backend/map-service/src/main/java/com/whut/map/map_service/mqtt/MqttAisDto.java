package com.whut.map.map_service.mqtt;

import com.whut.map.map_service.domain.AisMessage;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class MqttAisDto {

    private OffsetDateTime msgTime;
    private String mmsi;
    private double longitude;
    private double latitude;
    private double sog;
    private double cog;
    private double heading;

    public AisMessage toDomain() {
        int parsedMmsi = -1; // 默认值，表示解析失败

        if (this.mmsi != null && !this.mmsi.trim().isEmpty()) {
            try {
                parsedMmsi = Integer.parseInt(this.mmsi);
            } catch (NumberFormatException e) {
                // 记录日志或处理解析错误
                System.err.println("Invalid MMSI format: " + this.mmsi);
            }
        }

        return AisMessage.builder()
                .msgTime(this.msgTime)
                .mmsi(parsedMmsi) // 传入安全的 MMSI 值
                .longitude(this.longitude)
                .latitude(this.latitude)
                .sog(this.sog)
                .cog(this.cog)
                .heading(this.heading != 511 ? this.heading : null) // 511 表示未知
                .build();
    }
}
