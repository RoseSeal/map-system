package com.whut.map.map_service.assembler;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RiskObjectAssembler {
    private static final double METERS_PER_NAUTICAL_MILE = 1852.0;

    public RiskObjectDto assembleRiskObject(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult,
            ShipDomainResult domainResult,
            CvPredictionResult cvResult
    ) {
        if (ownShip == null) {
            return null;
        }

        String snapshotTimestamp = buildSnapshotTimestamp(allShips, ownShip);

        return RiskObjectDto.builder()
                .riskObjectId(ownShip.getId() + "-" + snapshotTimestamp)
                .timestamp(snapshotTimestamp)
                .governance(Map.of("mode", "adaptive", "trust_factor", 0.99))
                .ownShip(buildOwnShipData(ownShip))
                .targets(buildTargets(ownShip, allShips, cpaResults))
                .environmentContext(Map.of(
                        "safety_contour_val", 10.0,
                        "active_alerts", List.of()
                ))
                .build();
    }

    private Map<String, Object> buildOwnShipData(ShipStatus ownShip) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", ownShip.getLongitude());
        position.put("lat", ownShip.getLatitude());

        Map<String, Object> dynamics = new LinkedHashMap<>();
        dynamics.put("sog", ownShip.getSog());
        dynamics.put("cog", ownShip.getCog());
        dynamics.put("hdg", normalizedHeading(ownShip));
        dynamics.put("rot", 0.0);

        Map<String, Object> platformHealth = new LinkedHashMap<>();
        platformHealth.put("status", "NORMAL");
        platformHealth.put("description", "");

        Map<String, Object> futureTrajectory = new LinkedHashMap<>();
        futureTrajectory.put("prediction_type", "linear");

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("fore_nm", 0.5);
        dimensions.put("aft_nm", 0.1);
        dimensions.put("port_nm", 0.2);
        dimensions.put("stbd_nm", 0.2);

        Map<String, Object> safetyDomain = new LinkedHashMap<>();
        safetyDomain.put("shape_type", "ellipse");
        safetyDomain.put("dimensions", dimensions);

        Map<String, Object> ownShipData = new LinkedHashMap<>();
        ownShipData.put("id", ownShip.getId());
        ownShipData.put("position", position);
        ownShipData.put("dynamics", dynamics);
        ownShipData.put("platform_health", platformHealth);
        ownShipData.put("future_trajectory", futureTrajectory);
        ownShipData.put("safety_domain", safetyDomain);
        return ownShipData;
    }

    private List<Map<String, Object>> buildTargets(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults
    ) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (allShips == null) {
            return targets;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());
            targets.add(buildTarget(ownShip, ship, cpaResult));
        }
        return targets;
    }

    private Map<String, Object> buildTarget(ShipStatus ownShip, ShipStatus targetShip, CpaTcpaResult cpaResult) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", targetShip.getLongitude());
        position.put("lat", targetShip.getLatitude());

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("speed_kn", targetShip.getSog());
        vector.put("course_deg", targetShip.getCog());

        double dcpaNm = cpaResult == null ? 0.0 : metersToNm(cpaResult.getCpaDistance());
        double tcpaSec = cpaResult == null ? 0.0 : Math.max(cpaResult.getTcpaTime(), 0.0);
        String riskLevel = classifyRisk(dcpaNm, tcpaSec);

        Map<String, Object> cpaMetrics = new LinkedHashMap<>();
        cpaMetrics.put("dcpa_nm", dcpaNm);
        cpaMetrics.put("tcpa_sec", tcpaSec);

        Map<String, Object> riskAssessment = new LinkedHashMap<>();
        riskAssessment.put("risk_level", riskLevel);
        riskAssessment.put("cpa_metrics", cpaMetrics);

        if (cpaResult != null && cpaResult.isApproaching()) {
            Map<String, Object> graphicCpaLine = new LinkedHashMap<>();
            graphicCpaLine.put("own_pos", List.of(ownShip.getLongitude(), ownShip.getLatitude()));
            graphicCpaLine.put("target_pos", List.of(targetShip.getLongitude(), targetShip.getLatitude()));
            riskAssessment.put("graphic_cpa_line", graphicCpaLine);
        }

        if ("WARNING".equals(riskLevel) || "ALARM".equals(riskLevel)) {
            Map<String, Object> oztSector = new LinkedHashMap<>();
            oztSector.put("start_angle_deg", targetShip.getCog() - 10.0);
            oztSector.put("end_angle_deg", targetShip.getCog() + 10.0);
            oztSector.put("is_active", true);
            riskAssessment.put("ozt_sector", oztSector);
        }

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("source", "rule");
        explanation.put("text", cpaResult == null ? "Awaiting CPA/TCPA" : "CPA/TCPA derived risk");
        riskAssessment.put("explanation", explanation);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", targetShip.getId());
        target.put("tracking_status", "tracking");
        target.put("position", position);
        target.put("vector", vector);
        target.put("risk_assessment", riskAssessment);
        return target;
    }

    private String buildSnapshotTimestamp(Collection<ShipStatus> allShips, ShipStatus fallback) {
        OffsetDateTime latest = fallback.getMsgTime();
        if (allShips != null) {
            for (ShipStatus ship : allShips) {
                if (ship == null || ship.getMsgTime() == null) {
                    continue;
                }
                if (latest == null || ship.getMsgTime().isAfter(latest)) {
                    latest = ship.getMsgTime();
                }
            }
        }
        return latest == null ? OffsetDateTime.now().toInstant().toString() : latest.toInstant().toString();
    }

    private double normalizedHeading(ShipStatus shipStatus) {
        return shipStatus.getHeading() == null ? shipStatus.getCog() : shipStatus.getHeading();
    }

    private double metersToNm(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }

    private String classifyRisk(double dcpaNm, double tcpaSec) {
        if (dcpaNm <= 0.3 && tcpaSec > 0 && tcpaSec <= 300) {
            return "ALARM";
        }
        if (dcpaNm <= 0.5 && tcpaSec > 0 && tcpaSec <= 900) {
            return "WARNING";
        }
        if (dcpaNm <= 1.0 && tcpaSec > 0 && tcpaSec <= 1800) {
            return "CAUTION";
        }
        return "SAFE";
    }
}
