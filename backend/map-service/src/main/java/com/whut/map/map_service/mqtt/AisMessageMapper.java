package com.whut.map.map_service.mqtt;

import com.whut.map.map_service.config.AisProperties;
import com.whut.map.map_service.domain.ShipStatus;
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


    public ShipStatus toDomain(MqttAisDto mqttAisDto) {
        String ownShipMmsi = aisProperties.getOwnShipMmsi(); // 获取配置的本船 Id
        String timezone = aisProperties.getTimezone(); // 获取配置的时区
        Double confidence = aisProperties.getConfidence(); // 默认置信度，可以根据实际情况调整
        OffsetDateTime msgTime = null; // 用于存储解析后的时间
        String rawMmsi = mqttAisDto.getMmsi();

        if(rawMmsi == null) {
            log.debug("MMSI is null in incoming message");
            return null; // 或者抛出异常，取决于错误处理策略
        }
        String mmsi = rawMmsi.trim(); // 去除前后空格，避免格式问题

        if(mmsi.matches("\\d{9}")) {
            // MMSI 是一个9位数字字符串，直接使用
            log.debug("Parsed MMSI: {}", mmsi);
        } else {
            // 无效的 MMSI，直接失败
            log.debug("Invalid MMSI format: {}", mmsi);
            return null; // 或者抛出异常，取决于错误处理策略
        }

        mmsi = (mmsi.equals(ownShipMmsi)) ? "ownShip" : mmsi; // 如果是本船，id 统一为 "ownShip"，否则使用原始 MMSI 作为 id

        // 解析时间，确保它是一个有效的日期时间字符串
        if (mqttAisDto.getMsgTime() != null) {
            try {
                msgTime = mqttAisDto.getMsgTime().atZone(ZoneId.of(timezone)).toOffsetDateTime();
            } catch (Exception e) {
                // 记录日志或处理解析错误
                log.debug("Invalid msgTime format:{}", mqttAisDto.getMsgTime());
            }
        }

        // 识别ShipRole
        ShipRole computedRole = (mmsi.equals("ownShip"))
                ? ShipRole.OWN_SHIP
                : ShipRole.TARGET_SHIP;

        return ShipStatus.builder()
                .msgTime(msgTime)
                .id(mmsi) // 传入安全的 MMSI 值
                .longitude(mqttAisDto.getLongitude())
                .latitude(mqttAisDto.getLatitude())
                .sog(mqttAisDto.getSog())
                .cog(mqttAisDto.getCog())
                .heading(mqttAisDto.getHeading() != 511 ? mqttAisDto.getHeading() : null) // 511 表示未知
                .role(computedRole) // 状态一但确定，整个JVM生命周期内只读
                .confidence(confidence)
                .build();
    }
}
