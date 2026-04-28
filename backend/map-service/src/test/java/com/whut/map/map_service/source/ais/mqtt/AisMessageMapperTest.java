package com.whut.map.map_service.source.ais.mqtt;

import com.whut.map.map_service.source.ais.config.AisProperties;
import com.whut.map.map_service.shared.domain.QualityFlag;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AisMessageMapperTest {

    @Test
    void normalMessageUsesSourceConfidenceCapAndHasNoFlags() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(0.7));

        ShipStatus status = mapper.toDomain(baseDto());

        assertNotNull(status);
        assertEquals(0.7, status.getConfidence(), 1e-9);
        assertNotNull(status.getQualityFlags());
        assertTrue(status.getQualityFlags().isEmpty());
    }

    @Test
    void speedOutOfRangeAddsFlagAndDeductsConfidence() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(1.0));
        MqttAisDto dto = baseDto();
        dto.setSog(35.0);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.SPEED_OUT_OF_RANGE));
        assertEquals(0.7, status.getConfidence(), 1e-9);
    }

    @Test
    void negativeSpeedIsFlaggedAndClampedToZero() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(1.0));
        MqttAisDto dto = baseDto();
        dto.setSog(-3.0);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.SPEED_OUT_OF_RANGE));
        assertEquals(0.0, status.getSog(), 1e-9);
        assertEquals(0.7, status.getConfidence(), 1e-9);
    }

    @Test
    void positionOutOfRangeAddsFlagAndDeductsConfidence() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(1.0));
        MqttAisDto dto = baseDto();
        dto.setLatitude(95.0);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.POSITION_OUT_OF_RANGE));
        assertEquals(0.5, status.getConfidence(), 1e-9);
    }

    @Test
    void heading511AddsMissingHeadingFlag() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(1.0));
        MqttAisDto dto = baseDto();
        dto.setHeading(511.0);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.MISSING_HEADING));
        assertEquals(0.9, status.getConfidence(), 1e-9);
    }

    @Test
    void missingMsgTimeAddsMissingTimestampFlag() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(1.0));
        MqttAisDto dto = baseDto();
        dto.setMsgTime(null);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.MISSING_TIMESTAMP));
        assertEquals(0.8, status.getConfidence(), 1e-9);
    }

    @Test
    void sourceConfidenceCapStillAppliesAfterDeduction() {
        AisMessageMapper mapper = new AisMessageMapper(aisProperties(0.7));
        MqttAisDto dto = baseDto();
        dto.setHeading(511.0);

        ShipStatus status = mapper.toDomain(dto);

        assertNotNull(status);
        assertTrue(status.getQualityFlags().contains(QualityFlag.MISSING_HEADING));
        assertEquals(0.7, status.getConfidence(), 1e-9);
    }

    private static AisProperties aisProperties(double confidence) {
        AisProperties properties = new AisProperties();
        properties.setOwnShipMmsi("123456789");
        properties.setTimezone("UTC");
        properties.setConfidence(confidence);
        return properties;
    }

    private static MqttAisDto baseDto() {
        MqttAisDto dto = new MqttAisDto();
        dto.setMmsi("987654321");
        dto.setLongitude(114.30);
        dto.setLatitude(30.60);
        dto.setSog(10.0);
        dto.setCog(90.0);
        dto.setHeading(90.0);
        dto.setMsgTime(LocalDateTime.of(2026, 4, 13, 12, 0, 0));
        return dto;
    }
}
