package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.config.RiskAssessmentProperties;
import com.whut.map.map_service.risk.config.RiskScoringProperties;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.util.GeoUtils;
import com.whut.map.map_service.shared.util.MathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class RiskAssessmentEngine {
    // TCPA容差：允许轻微负值，避免数值抖动导致“刚刚错过”被误判为远离。
    private static final double TCPA_CPA_EPS_SEC = 1.0;

    private final RiskAssessmentProperties riskProperties;
    private final RiskScoringProperties scoringProperties;
    private final DomainPenetrationCalculator domainPenetrationCalculator;
    private final PredictedCpaCalculator predictedCpaCalculator;

    public RiskAssessmentEngine(
            RiskAssessmentProperties riskProperties,
            RiskScoringProperties scoringProperties,
            DomainPenetrationCalculator domainPenetrationCalculator,
            PredictedCpaCalculator predictedCpaCalculator) {
        this.riskProperties = riskProperties;
        this.scoringProperties = scoringProperties;
        this.domainPenetrationCalculator = domainPenetrationCalculator;
        this.predictedCpaCalculator = predictedCpaCalculator;
    }

    public RiskAssessmentResult consume(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            ShipDomainResult shipDomainResult,
            Map<String, CvPredictionResult> cvPredictionResults,
            Map<String, EncounterClassificationResult> encounterResults
    ) {
        // 输入不完整时返回空结果，避免下游空指针。
        if (ownShip == null || allShips == null) {
            return RiskAssessmentResult.empty();
        }

        log.debug("Aggregating risk assessment for ownShip={}, targets={}", ownShip.getId(), allShips.size());

        Map<String, TargetRiskAssessment> assessments = new LinkedHashMap<>();
        for (ShipStatus ship : allShips) {
            // 跳过空目标、无ID目标和本船自身。
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }
            // 每个目标ID分别拉取CPA/预测/会遇结果，再聚合为单目标风险评估。
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());
            CvPredictionResult predictionResult = cvPredictionResults == null ? null : cvPredictionResults.get(ship.getId());
            EncounterClassificationResult encounterResult = encounterResults == null ? null : encounterResults.get(ship.getId());
            
            TargetRiskAssessment assessment = buildTargetAssessment(
                    ship.getId(), 
                    cpaResult,
                    ownShip,
                    ship,
                    shipDomainResult,
                    predictionResult,
                    encounterResult
            );
            assessments.put(ship.getId(), assessment);
        }

        return RiskAssessmentResult.builder()
                .targetAssessments(assessments)
                .build();
    }

    public TargetRiskAssessment buildTargetAssessment(
            String targetId, 
            CpaTcpaResult cpaResult,
            ShipStatus ownShip,
            ShipStatus targetShip,
            ShipDomainResult domainResult,
            CvPredictionResult predictionResult,
            EncounterClassificationResult encounterResult
    ) {
        // 无cpa/tcap直接返回，解释文本为等待cpa
        if (cpaResult == null) {
            return TargetRiskAssessment.builder()
                    .targetId(targetId)
                    .riskLevel(RiskConstants.SAFE)
                    .cpaDistanceMeters(0.0)
                    .tcpaSeconds(0.0)
                    .approaching(false)
                    .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                    .explanationText(RiskConstants.EXPLANATION_TEXT_AWAITING_CPA)
                    .build();
        }

        // 几何风险基础量：DCPA(最近会遇距离) + TCPA(到最近会遇时间)。
        double cpaDistanceMeters = cpaResult.getCpaDistance();
        double dcpaNm = GeoUtils.metersToNm(cpaDistanceMeters);
        double rawTcpaSec = cpaResult.getTcpaTime();
        
        String riskLevel;
        double tcpaScore = 0.0;
        boolean approaching = false;

        if (!cpaResult.isCpaValid()) {
            // Relative motion is too small to determine convergence (parallel / stationary ships).
            // No approaching trend → riskLevel stays SAFE regardless of current separation.
            // riskScore still captures proximity via dcpaScore (tcpaScore = 0 by default above).
            riskLevel = RiskConstants.SAFE;
        } else {
            // 离散等级由阈值规则决定。
            riskLevel = classifyRisk(dcpaNm, rawTcpaSec);
            approaching = (rawTcpaSec >= -TCPA_CPA_EPS_SEC);
            if (approaching) {
                // TCPA越小越危险，线性映射到[0,1]。
                tcpaScore = 1.0 - MathUtils.clamp(rawTcpaSec / riskProperties.getCautionTcpaSec(), 0, 1);
            }
        }

        // DCPA越小越危险，几何分取DCPA与TCPA均值。
        double dcpaScore = 1.0 - MathUtils.clamp(dcpaNm / riskProperties.getCautionDcpaNm(), 0, 1);
        double geometryScore = (dcpaScore + tcpaScore) / 2.0;

        if (cpaResult.isCpaValid() && predictionResult != null) {
            // 用轨迹预测对几何分做微调：变危险加分幅度大于变安全减分幅度。
            double[] predictedCpa = predictedCpaCalculator.calculate(ownShip, predictionResult);
            if (predictedCpa != null) {
                double predictedCpaNm = predictedCpa[0];
                double denominator = Math.max(dcpaNm, 0.001);
                if (dcpaNm > 0) {
                    if (predictedCpaNm < dcpaNm) {
                        double worseningRatio = MathUtils.clamp((dcpaNm - predictedCpaNm) / denominator, 0, 0.3);
                        geometryScore = MathUtils.clamp(geometryScore + worseningRatio, 0, 1);
                    } else {
                        double easingRatio = MathUtils.clamp((predictedCpaNm - dcpaNm) / denominator, 0, 0.15);
                        geometryScore = MathUtils.clamp(geometryScore - easingRatio, 0, 1);
                    }
                }
            }
        }

        // 船域侵入分量：用于描述“空间压迫”风险。
        Double penetration = domainPenetrationCalculator.calculate(ownShip, targetShip, domainResult);
        double domainScore = 0.0;
        double finalRiskScore;
        // 会遇修正因子：不同会遇类型对应不同风险放大/缩小权重。
        double encounterModifier = scoringProperties.getUndefinedModifier();
        
        EncounterType encounterType = encounterResult != null ? encounterResult.getEncounterType() : null;
        if (encounterType != null && encounterType != EncounterType.UNDEFINED) {
            switch (encounterType) {
                case HEAD_ON:
                    encounterModifier = scoringProperties.getHeadOnModifier();
                    break;
                case CROSSING:
                    encounterModifier = scoringProperties.getCrossingModifier();
                    break;
                case OVERTAKING:
                    encounterModifier = scoringProperties.getOvertakingModifier();
                    break;
                default:
                    break;
            }
        }

        if (domainResult != null && penetration != null) {
            domainScore = MathUtils.clamp(penetration, 0, 1);
            // 几何风险与船域风险按权重融合，再乘会遇修正。
            finalRiskScore = (scoringProperties.getGeometryWeight() * geometryScore + scoringProperties.getDomainWeight() * domainScore) * encounterModifier;
        } else {
            finalRiskScore = geometryScore * encounterModifier;
        }
        
        finalRiskScore = MathUtils.clamp(finalRiskScore, 0.0, 1.0);

        // 置信度取本船与目标船中的较低值，体现“短板效应”。
        double riskConfidence = Math.min(
                ownShip.getConfidence() != null ? ownShip.getConfidence() : 1.0,
                targetShip.getConfidence() != null ? targetShip.getConfidence() : 1.0
        );

        return TargetRiskAssessment.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .cpaDistanceMeters(cpaDistanceMeters)
                .tcpaSeconds(cpaResult.isCpaValid() ? Math.max(rawTcpaSec, 0.0) : 0.0)
                .approaching(approaching)
                .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                .explanationText(RiskConstants.EXPLANATION_TEXT_DERIVED)
                .riskScore(finalRiskScore)
                .riskConfidence(riskConfidence)
                .encounterType(encounterType)
                .domainPenetration(penetration)
                .build();
    }

    private String classifyRisk(double dcpaNm, double tcpaSec) {
        // 按 alarm -> warning -> caution 逐级判定，命中即返回。
        if (dcpaNm <= riskProperties.getAlarmDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getAlarmTcpaSec()) {
            return RiskConstants.ALARM;
        }
        if (dcpaNm <= riskProperties.getWarningDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getWarningTcpaSec()) {
            return RiskConstants.WARNING;
        }
        if (dcpaNm <= riskProperties.getCautionDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getCautionTcpaSec()) {
            return RiskConstants.CAUTION;
        }
        return RiskConstants.SAFE;
    }
}
