package com.whut.map.map_service.assembler;

import com.whut.map.map_service.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.llm.dto.LlmExplanation;
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
            Map<String, LlmExplanation> llmExplanations,
            ShipDomainResult domainResult,
            CvPredictionResult cvResult
    ) {
        if (ownShip == null) {
            return null;
        }

        String snapshotTimestamp = riskObjectMetaAssembler.buildSnapshotTimestamp(allShips, ownShip);

        return RiskObjectDto.builder()
                .riskObjectId(riskObjectMetaAssembler.buildRiskObjectId(ownShip, snapshotTimestamp))
                .timestamp(snapshotTimestamp)
                .governance(riskObjectMetaAssembler.buildGovernance())
                .ownShip(ownShipAssembler.assemble(ownShip, domainResult, cvResult))
                .targets(targetAssembler.assembleTargets(ownShip, allShips, cpaResults, riskResult, llmExplanations))
                .environmentContext(riskObjectMetaAssembler.buildEnvironmentContext())
                .build();
    }
}

