package com.whut.map.map_service.llm.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MemoryGraphLoader {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final LlmProperties llmProperties;

    @Bean
    @ConditionalOnProperty(prefix = "llm.graph", name = "enabled", havingValue = "true")
    public GraphQueryPort graphQueryPort() {
        String resourcePath = llmProperties.getGraph().getResourcePath();
        try (InputStream in = resourceLoader.getResource(resourcePath).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, ManeuverAction> actionIndex = loadActions(root);
            List<Rule> rules = loadRules(root, actionIndex);
            log.info("COLREGS graph loaded: {} rules, {} actions from {}", rules.size(), actionIndex.size(), resourcePath);
            return new MemoryGraphAdapter(rules, actionIndex);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load COLREGS graph from " + resourcePath, e);
        }
    }

    private Map<String, ManeuverAction> loadActions(JsonNode root) {
        JsonNode actionsNode = root.get("maneuverActions");
        if (actionsNode == null || !actionsNode.isArray()) {
            throw new IllegalStateException("colregs/rules.json: missing or invalid 'maneuverActions' array");
        }

        Map<String, ManeuverAction> index = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonNode node : actionsNode) {
            String actionId = requiredText(node, "actionId", "maneuverAction");
            if (!seenIds.add(actionId)) {
                throw new IllegalStateException("colregs/rules.json: duplicate actionId '" + actionId + "'");
            }
            ManeuverActionType type = parseEnum(ManeuverActionType.class, requiredText(node, "type", "maneuverAction[" + actionId + "]"));
            ManeuverAction action = new ManeuverAction(
                    actionId,
                    type,
                    requiredText(node, "descriptionEn", "maneuverAction[" + actionId + "]"),
                    requiredText(node, "descriptionZh", "maneuverAction[" + actionId + "]"),
                    textOrNull(node, "rationale")
            );
            index.put(actionId, action);
        }
        return index;
    }

    private List<Rule> loadRules(JsonNode root, Map<String, ManeuverAction> actionIndex) {
        JsonNode rulesNode = root.get("rules");
        if (rulesNode == null || !rulesNode.isArray()) {
            throw new IllegalStateException("colregs/rules.json: missing or invalid 'rules' array");
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> seenRuleIds = new HashSet<>();
        Set<String> seenRuleNumbers = new HashSet<>();

        for (JsonNode node : rulesNode) {
            String ruleId = requiredText(node, "ruleId", "rule");
            if (!seenRuleIds.add(ruleId)) {
                throw new IllegalStateException("colregs/rules.json: duplicate ruleId '" + ruleId + "'");
            }
            String ruleNumber = requiredText(node, "ruleNumber", "rule[" + ruleId + "]");
            if (!seenRuleNumbers.add(ruleNumber)) {
                throw new IllegalStateException("colregs/rules.json: duplicate ruleNumber '" + ruleNumber + "'");
            }

            List<EncounterType> situations = parseEncounterTypes(node.get("applicableSituations"), ruleId);
            List<OwnShipRole> roles = parseOwnShipRoles(node.get("applicableRoles"), ruleId);
            List<String> recommendedActionIds = parseStringList(node.get("recommendedActionIds"));
            List<String> limitations = parseStringList(node.get("limitations"));

            for (String actionId : recommendedActionIds) {
                if (!actionIndex.containsKey(actionId)) {
                    throw new IllegalStateException(
                            "colregs/rules.json: rule '" + ruleId + "' references unknown actionId '" + actionId + "'");
                }
            }

            rules.add(new Rule(
                    ruleId,
                    ruleNumber,
                    requiredText(node, "title", "rule[" + ruleId + "]"),
                    textOrNull(node, "part"),
                    textOrNull(node, "section"),
                    requiredText(node, "summaryEn", "rule[" + ruleId + "]"),
                    textOrNull(node, "summaryZh"),
                    textOrNull(node, "principle"),
                    requiredText(node, "sourceCitation", "rule[" + ruleId + "]"),
                    situations,
                    roles,
                    recommendedActionIds,
                    limitations,
                    textOrNull(node, "fullText")
            ));
        }
        return rules;
    }

    private List<EncounterType> parseEncounterTypes(JsonNode node, String ruleId) {
        List<EncounterType> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        for (JsonNode item : node) {
            String val = item.asText(null);
            if (val == null || val.isBlank()) continue;
            try {
                result.add(EncounterType.valueOf(val));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "colregs/rules.json: rule '" + ruleId + "' has unknown encounterType '" + val + "'");
            }
        }
        return result;
    }

    private List<OwnShipRole> parseOwnShipRoles(JsonNode node, String ruleId) {
        List<OwnShipRole> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        for (JsonNode item : node) {
            String val = item.asText(null);
            if (val == null || val.isBlank()) continue;
            try {
                result.add(OwnShipRole.valueOf(val));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "colregs/rules.json: rule '" + ruleId + "' has unknown ownShipRole '" + val + "'");
            }
        }
        return result;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        for (JsonNode item : node) {
            String val = item.asText(null);
            if (val != null && !val.isBlank()) result.add(val);
        }
        return result;
    }

    private String requiredText(JsonNode node, String field, String context) {
        JsonNode f = node.get(field);
        if (f == null || f.isNull() || f.asText("").isBlank()) {
            throw new IllegalStateException(
                    "colregs/rules.json: missing required field '" + field + "' in " + context);
        }
        return f.asText();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        String val = f.asText(null);
        return (val == null || val.isBlank()) ? null : val;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("colregs/rules.json: unknown enum value '" + value + "' for " + enumClass.getSimpleName());
        }
    }
}
