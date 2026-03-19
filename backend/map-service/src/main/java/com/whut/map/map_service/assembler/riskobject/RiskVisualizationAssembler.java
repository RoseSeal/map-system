package com.whut.map.map_service.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskConstants;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RiskVisualizationAssembler {

    public Map<String, Object> buildGraphicCpaLine(ShipStatus ownShip, ShipStatus targetShip, CpaTcpaResult cpaResult) {
        if (cpaResult == null || !cpaResult.isApproaching()) {
            return null;
        }

        Map<String, Object> graphicCpaLine = new LinkedHashMap<>();
        graphicCpaLine.put("own_pos", List.of(ownShip.getLongitude(), ownShip.getLatitude()));
        graphicCpaLine.put("target_pos", List.of(targetShip.getLongitude(), targetShip.getLatitude()));
        return graphicCpaLine;
    }

    public Map<String, Object> buildOztSector(ShipStatus targetShip, String riskLevel) {
        if (!RiskConstants.WARNING.equals(riskLevel) && !RiskConstants.ALARM.equals(riskLevel)) {
            return null;
        }

        Map<String, Object> oztSector = new LinkedHashMap<>();
        oztSector.put("start_angle_deg", targetShip.getCog() - 10.0);
        oztSector.put("end_angle_deg", targetShip.getCog() + 10.0);
        oztSector.put("is_active", true);
        return oztSector;
    }
}
