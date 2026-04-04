package com.whut.map.map_service.transport.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.sse.ExplanationPayload;
import com.whut.map.map_service.dto.sse.RiskUpdatePayload;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SseEventFactory {

    private final ObjectMapper objectMapper;

    public String generateEventId() {
        return UUID.randomUUID().toString();
    }

    public RiskUpdatePayload buildRiskUpdate(RiskObjectDto riskObject) {
        if (riskObject == null) {
            return null;
        }

        return RiskUpdatePayload.builder()
                .eventId(generateEventId())
                .riskObjectId(riskObject.getRiskObjectId())
                .timestamp(riskObject.getTimestamp())
                .governance(riskObject.getGovernance())
                .ownShip(riskObject.getOwnShip())
                .targets(removeExplanation(riskObject.getTargets()))
                .environmentContext(riskObject.getEnvironmentContext())
                .build();
    }

    public ExplanationPayload buildExplanation(LlmExplanation explanation, String riskObjectId) {
        if (explanation == null) {
            return null;
        }

        return ExplanationPayload.builder()
                .eventId(generateEventId())
                .riskObjectId(riskObjectId)
                .targetId(explanation.getTargetId())
                .riskLevel(explanation.getRiskLevel())
                .provider(explanation.getProvider() != null ? explanation.getProvider() : explanation.getSource())
                .text(explanation.getText())
                .timestamp(explanation.getTimestamp() != null ? explanation.getTimestamp() : Instant.now().toString())
                .build();
    }

    private Object removeExplanation(Object targets) {
        if (targets == null) {
            return null;
        }

        JsonNode root = objectMapper.valueToTree(targets);
        if (!root.isArray()) {
            return objectMapper.convertValue(root, Object.class);
        }

        for (JsonNode targetNode : root) {
            JsonNode riskAssessmentNode = targetNode.get("risk_assessment");
            if (riskAssessmentNode instanceof ObjectNode objectNode) {
                objectNode.remove("explanation");
            }
        }

        return objectMapper.convertValue(root, Object.class);
    }
}
