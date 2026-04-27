package com.whut.map.map_service.risk.pipeline.assembler;

import com.whut.map.map_service.risk.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
public class RiskObjectAssembler {

    private final RiskObjectMetaAssembler riskObjectMetaAssembler;
    private final OwnShipAssembler ownShipAssembler;
    private final TargetAssembler targetAssembler;

    public RiskObjectAssembler(
            RiskObjectMetaAssembler riskObjectMetaAssembler,
            OwnShipAssembler ownShipAssembler,
            TargetAssembler targetAssembler
    ) {
        this.riskObjectMetaAssembler = riskObjectMetaAssembler;
        this.ownShipAssembler = ownShipAssembler;
        this.targetAssembler = targetAssembler;
    }

    public RiskObjectDto assembleRiskObject(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            Map<String, PredictedCpaTcpaResult> predictedCpaResults,
            RiskAssessmentResult riskResult,
            ShipDomainResult domainResult,
            Map<String, CvPredictionResult> cvResults,
            Map<String, EncounterClassificationResult> encounterResults
    ) {
        if (ownShip == null) {
            return null;
        }

        String snapshotTimestamp = riskObjectMetaAssembler.buildSnapshotTimestamp(allShips, ownShip);
        double trustFactor = computeOwnShipTrustFactor(ownShip);

        return RiskObjectDto.builder()
                .riskObjectId(riskObjectMetaAssembler.buildRiskObjectId(ownShip, snapshotTimestamp))
                .timestamp(snapshotTimestamp)
                .governance(riskObjectMetaAssembler.buildGovernance(trustFactor))
                .ownShip(ownShipAssembler.assemble(ownShip, domainResult))
                .targets(targetAssembler.assembleTargets(
                        ownShip, allShips, cpaResults, riskResult, cvResults, predictedCpaResults, encounterResults))
                .environmentContext(riskObjectMetaAssembler.buildEnvironmentContext(ownShip))
                .build();
    }

    private double computeOwnShipTrustFactor(ShipStatus ownShip) {
        Double confidence = ownShip.getConfidence();
        if (confidence == null || Double.isNaN(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
