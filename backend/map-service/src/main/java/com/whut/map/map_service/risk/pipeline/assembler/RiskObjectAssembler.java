package com.whut.map.map_service.risk.pipeline.assembler;

import com.whut.map.map_service.risk.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
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
            RiskAssessmentResult riskResult,
            ShipDomainResult domainResult,
            Map<String, CvPredictionResult> cvResults,
            Map<String, EncounterClassificationResult> encounterResults
    ) {
        if (ownShip == null) {
            return null;
        }

        String snapshotTimestamp = riskObjectMetaAssembler.buildSnapshotTimestamp(allShips, ownShip);
        double trustFactor = computeAvgConfidence(riskResult);

        return RiskObjectDto.builder()
                .riskObjectId(riskObjectMetaAssembler.buildRiskObjectId(ownShip, snapshotTimestamp))
                .timestamp(snapshotTimestamp)
                .governance(riskObjectMetaAssembler.buildGovernance(trustFactor))
                .ownShip(ownShipAssembler.assemble(ownShip, domainResult))
                .targets(targetAssembler.assembleTargets(
                        ownShip, allShips, cpaResults, riskResult, cvResults, encounterResults))
                .environmentContext(riskObjectMetaAssembler.buildEnvironmentContext())
                .build();
    }

    private double computeAvgConfidence(RiskAssessmentResult riskResult) {
        if (riskResult == null
                || riskResult.getTargetAssessments() == null
                || riskResult.getTargetAssessments().isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        int count = 0;
        for (TargetRiskAssessment assessment : riskResult.getTargetAssessments().values()) {
            if (assessment == null || Double.isNaN(assessment.getRiskConfidence())) {
                continue;
            }
            sum += assessment.getRiskConfidence();
            count += 1;
        }
        return count == 0 ? 0.0 : sum / count;
    }
}
