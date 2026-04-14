package com.whut.map.map_service.engine;

import com.whut.map.map_service.config.properties.AisQualityProperties;
import com.whut.map.map_service.domain.QualityFlag;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShipKinematicQualityCheckerTest {

    private ShipKinematicQualityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ShipKinematicQualityChecker(new AisQualityProperties());
    }

    @Test
    void skipsCheckWhenCurrentTimestampIsMissing() {
        ShipStatus previous = ship(120.0, 30.0, 10.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(120.5, 30.5, 10.0, 90.0, null, 0.8, Set.of(QualityFlag.MISSING_TIMESTAMP));

        ShipStatus result = checker.check(current, previous);

        assertThat(result).isSameAs(current);
    }

    @Test
    void acceptsEmptyQualityFlagsWithoutThrowing() {
        ShipStatus previous = ship(120.0000, 30.0000, 8.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 1.0, Set.of());
        ShipStatus current = ship(120.0010, 30.0000, 8.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:30+08:00"), 1.0, Collections.emptySet());

        ShipStatus result = checker.check(current, previous);

        assertThat(result.getQualityFlags()).isEmpty();
        assertThat(result.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void marksPositionJumpAndReducesConfidence() {
        ShipStatus previous = ship(120.0000, 30.0000, 12.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.95, Set.of());
        ShipStatus current = ship(120.1000, 30.0000, 12.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:30+08:00"), 0.95, Set.of());

        ShipStatus result = checker.check(current, previous);

        assertThat(result.getQualityFlags()).contains(QualityFlag.POSITION_JUMP);
        assertThat(result.getConfidence()).isCloseTo(0.55, within(1e-9));
    }

    @Test
    void doesNotFlagSmallDriftForStationaryShip() {
        ShipStatus previous = ship(120.000000, 30.000000, 0.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(120.000010, 30.000000, 0.0, 90.0, OffsetDateTime.parse("2026-04-14T10:01:00+08:00"), 0.9, Set.of());

        ShipStatus result = checker.check(current, previous);

        assertThat(result).isSameAs(current);
    }

    @Test
    void marksSogJump() {
        ShipStatus previous = ship(120.0, 30.0, 8.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(120.0, 30.0, 20.5, 90.0, OffsetDateTime.parse("2026-04-14T10:00:30+08:00"), 0.9, Set.of());

        ShipStatus result = checker.check(current, previous);

        assertThat(result.getQualityFlags()).contains(QualityFlag.SOG_JUMP);
        assertThat(result.getConfidence()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void doesNotFlagCogWrapAcrossZero() {
        ShipStatus previous = ship(120.0, 30.0, 8.0, 355.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(120.0, 30.0, 8.0, 5.0, OffsetDateTime.parse("2026-04-14T10:00:30+08:00"), 0.9, Set.of());

        ShipStatus result = checker.check(current, previous);

        assertThat(result).isSameAs(current);
    }

    @Test
    void marksLargeCogJump() {
        ShipStatus previous = ship(120.0, 30.0, 8.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(120.0, 30.0, 8.0, 200.0, OffsetDateTime.parse("2026-04-14T10:00:30+08:00"), 0.9, Set.of());

        ShipStatus result = checker.check(current, previous);

        assertThat(result.getQualityFlags()).contains(QualityFlag.COG_JUMP);
        assertThat(result.getConfidence()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void mergesFlagsAndAppliesCombinedDeduction() {
        ShipStatus previous = ship(120.0000, 30.0000, 5.0, 90.0, OffsetDateTime.parse("2026-04-14T10:00:00+08:00"), 0.9, Set.of());
        ShipStatus current = ship(
                120.1000,
                30.0000,
                20.0,
                220.0,
                OffsetDateTime.parse("2026-04-14T10:00:30+08:00"),
                0.9,
                Set.of(QualityFlag.MISSING_HEADING)
        );

        ShipStatus result = checker.check(current, previous);

        assertThat(result.getQualityFlags()).containsExactlyInAnyOrder(
                QualityFlag.MISSING_HEADING,
                QualityFlag.POSITION_JUMP,
                QualityFlag.SOG_JUMP,
                QualityFlag.COG_JUMP
        );
        assertThat(result.getConfidence()).isCloseTo(0.15, within(1e-9));
    }

    private ShipStatus ship(
            double longitude,
            double latitude,
            double sog,
            double cog,
            OffsetDateTime msgTime,
            double confidence,
            Set<QualityFlag> qualityFlags
    ) {
        return ShipStatus.builder()
                .id("target-1")
                .role(ShipRole.TARGET_SHIP)
                .longitude(longitude)
                .latitude(latitude)
                .sog(sog)
                .cog(cog)
                .heading(cog)
                .msgTime(msgTime)
                .confidence(confidence)
                .qualityFlags(qualityFlags)
                .build();
    }
}
