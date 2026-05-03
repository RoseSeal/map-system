package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmRiskContextDeepCopier {

    public LlmRiskContext copy(LlmRiskContext source) {
        if (source == null) return null;
        return LlmRiskContext.builder()
                .ownShip(copyOwnShip(source.getOwnShip()))
                .targets(copyTargets(source.getTargets()))
                .weather(copyWeather(source.getWeather()))
                .build();
    }

    private LlmRiskWeatherContext copyWeather(LlmRiskWeatherContext source) {
        if (source == null) return null;
        return LlmRiskWeatherContext.builder()
                .weatherCode(source.getWeatherCode())
                .visibilityNm(source.getVisibilityNm())
                .windSpeedKn(source.getWindSpeedKn())
                .windDirectionFromDeg(source.getWindDirectionFromDeg())
                .surfaceCurrentSpeedKn(source.getSurfaceCurrentSpeedKn())
                .surfaceCurrentSetDeg(source.getSurfaceCurrentSetDeg())
                .seaState(source.getSeaState())
                .sourceZoneId(source.getSourceZoneId())
                .activeAlerts(source.getActiveAlerts() == null ? null : List.copyOf(source.getActiveAlerts()))
                .build();
    }

    private LlmRiskOwnShipContext copyOwnShip(LlmRiskOwnShipContext source) {
        if (source == null) return null;
        return LlmRiskOwnShipContext.builder()
                .id(source.getId())
                .longitude(source.getLongitude())
                .latitude(source.getLatitude())
                .sog(source.getSog())
                .cog(source.getCog())
                .heading(source.getHeading())
                .confidence(source.getConfidence())
                .build();
    }

    private List<LlmRiskTargetContext> copyTargets(List<LlmRiskTargetContext> sources) {
        if (sources == null) return null;
        return sources.stream().map(this::copyTarget).toList();
    }

    private LlmRiskTargetContext copyTarget(LlmRiskTargetContext source) {
        if (source == null) return null;
        return LlmRiskTargetContext.builder()
                .targetId(source.getTargetId())
                .riskLevel(source.getRiskLevel())
                .currentDistanceNm(source.getCurrentDistanceNm())
                .relativeBearingDeg(source.getRelativeBearingDeg())
                .dcpaNm(source.getDcpaNm())
                .tcpaSec(source.getTcpaSec())
                .approaching(source.isApproaching())
                .longitude(source.getLongitude())
                .latitude(source.getLatitude())
                .speedKn(source.getSpeedKn())
                .courseDeg(source.getCourseDeg())
                .confidence(source.getConfidence())
                .riskScore(source.getRiskScore())
                .domainPenetration(source.getDomainPenetration())
                .ruleExplanation(source.getRuleExplanation())
                .encounterType(source.getEncounterType())
                .build();
    }
}
