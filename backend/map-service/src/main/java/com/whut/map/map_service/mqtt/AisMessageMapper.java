package com.whut.map.map_service.mqtt;

import com.whut.map.map_service.config.AisProperties;
import com.whut.map.map_service.domain.AisMessage;
import com.whut.map.map_service.domain.ShipRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

@Slf4j
@Component
public class AisMessageMapper {

    private final AisProperties aisProperties;

    public AisMessageMapper(AisProperties aisProperties) {
        this.aisProperties = aisProperties;
    }


    public AisMessage toDomain(MqttAisDto mqttAisDto) {
        int parsedMmsi = -1; // 默认值，表示解析失败
        int ownShipMmsi = aisProperties.getOwnShipMmsi(); // 获取配置的本船 MMSI
        OffsetDateTime msgTime = null; // 用于存储解析后的时间

        // 解析 MMSI，确保它是一个有效的整数
        if (mqttAisDto.getMmsi() != null && !mqttAisDto.getMmsi().trim().isEmpty()) {
            try {
                parsedMmsi = Integer.parseInt(mqttAisDto.getMmsi());
            } catch (NumberFormatException e) {
                // 记录日志或处理解析错误
                log.debug("Invalid MMSI format: {}", mqttAisDto.getMmsi());
            }
        }

        // 解析时间，确保它是一个有效的日期时间字符串
        if (mqttAisDto.getMsgTime() != null) {
            try {
                msgTime = mqttAisDto.getMsgTime().atZone(ZoneId.of(aisProperties.getTimezone())).toOffsetDateTime();
            } catch (Exception e) {
                // 记录日志或处理解析错误
                log.debug("Invalid msgTime format:{}", mqttAisDto.getMsgTime());
            }
        }

        // 识别ShipRole
        ShipRole computedRole = (parsedMmsi == ownShipMmsi)
                ? ShipRole.OWN_SHIP
                : ShipRole.TARGET_SHIP;

        return AisMessage.builder()
                .msgTime(msgTime)
                .mmsi(parsedMmsi) // 传入安全的 MMSI 值
                .longitude(mqttAisDto.getLongitude())
                .latitude(mqttAisDto.getLatitude())
                .sog(mqttAisDto.getSog())
                .cog(mqttAisDto.getCog())
                .heading(mqttAisDto.getHeading() != 511 ? mqttAisDto.getHeading() : null) // 511 表示未知
                .role(computedRole) // 状态一但确定，整个JVM生命周期内只读
                .build();
    }
}
