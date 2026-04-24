package com.whut.map.map_service.llm.agent.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.shared.dto.sse.AdvisoryActionType;
import com.whut.map.map_service.shared.dto.sse.AdvisoryUrgency;
import com.whut.map.map_service.shared.dto.sse.RecommendedAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryOutputParser {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public ParsedAdvisory parse(String text, AgentSnapshot snapshot) {
        String json = extractJson(text);
        if (json == null) {
            log.warn("Advisory output parser: no JSON found in LLM output");
            return null;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Advisory output parser: invalid JSON - {}", e.getMessage());
            return null;
        }

        String summary = textField(root, "summary");
        if (summary == null || summary.isBlank()) {
            log.warn("Advisory output parser: missing or blank 'summary'");
            return null;
        }

        List<String> affectedTargets = stringList(root, "affected_targets");
        if (affectedTargets == null || affectedTargets.isEmpty()) {
            log.warn("Advisory output parser: missing or empty 'affected_targets'");
            return null;
        }
        if (!validateTargetIds(affectedTargets, snapshot)) {
            log.warn("Advisory output parser: affected_targets contains unknown target IDs");
            return null;
        }

        RecommendedAction action = parseRecommendedAction(root);
        if (action == null) {
            return null;
        }

        List<String> evidenceItems = stringList(root, "evidence_items");
        if (evidenceItems == null || evidenceItems.isEmpty()) {
            log.warn("Advisory output parser: missing or empty 'evidence_items'");
            return null;
        }

        return new ParsedAdvisory(summary, affectedTargets, action, evidenceItems);
    }

    private String extractJson(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = JSON_BLOCK.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String textField(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private List<String> stringList(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String val = item.asText(null);
            if (val != null && !val.isBlank()) {
                result.add(val);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private boolean validateTargetIds(List<String> affectedTargets, AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return false;
        }
        Set<String> knownIds = snapshot.riskContext().getTargets().stream()
                .map(t -> t.getTargetId())
                .collect(Collectors.toSet());
        return knownIds.containsAll(affectedTargets);
    }

    private RecommendedAction parseRecommendedAction(JsonNode root) {
        JsonNode actionNode = root.get("recommended_action");
        if (actionNode == null || actionNode.isNull()) {
            log.warn("Advisory output parser: missing 'recommended_action'");
            return null;
        }

        String description = textField(actionNode, "description");
        if (description == null || description.isBlank()) {
            log.warn("Advisory output parser: missing 'recommended_action.description'");
            return null;
        }

        AdvisoryActionType type = parseEnum(AdvisoryActionType.class, textField(actionNode, "type"), AdvisoryActionType.UNKNOWN);
        AdvisoryUrgency urgency = parseEnum(AdvisoryUrgency.class, textField(actionNode, "urgency"), AdvisoryUrgency.MEDIUM);

        return RecommendedAction.builder()
                .type(type)
                .description(description)
                .urgency(urgency)
                .build();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public record ParsedAdvisory(
            String summary,
            List<String> affectedTargets,
            RecommendedAction recommendedAction,
            List<String> evidenceItems
    ) {}
}
