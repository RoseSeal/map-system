package com.whut.map.map_service.llm.agent.graph;

import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MemoryGraphAdapter implements GraphQueryPort {

    private static final String INSUFFICIENT = "insufficient_classification_input";
    private static final String VISIBILITY_NOT_INTEGRATED = "visibility_condition_not_integrated_with_weather_context";

    private final List<Rule> rules;
    private final Map<String, ManeuverAction> actionIndex;

    public MemoryGraphAdapter(List<Rule> rules, Map<String, ManeuverAction> actionIndex) {
        this.rules = rules;
        this.actionIndex = actionIndex;
    }

    @Override
    public RegulatoryContext findRegulatoryContext(RegulatoryQuery query) {
        EncounterType encounterType = query.encounterType();
        OwnShipRole ownShipRole = query.ownShipRole();

        if (encounterType == null || encounterType == EncounterType.UNDEFINED
                || ownShipRole == null || ownShipRole == OwnShipRole.UNKNOWN) {
            return new RegulatoryContext(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    List.of(INSUFFICIENT)
            );
        }

        boolean openVisibility = query.visibilityCondition() == null
                || query.visibilityCondition() == VisibilityCondition.OPEN_VISIBILITY;

        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.applicableSituations().isEmpty() || rule.applicableRoles().isEmpty()) {
                continue;
            }
            if (openVisibility && rule.limitations() != null
                    && rule.limitations().contains(VISIBILITY_NOT_INTEGRATED)) {
                continue;
            }
            if (rule.applicableSituations().contains(encounterType)
                    && rule.applicableRoles().contains(ownShipRole)) {
                matched.add(rule);
            }
        }

        matched.sort(Comparator.comparingInt((Rule r) -> Integer.parseInt(r.ruleNumber()))
                .thenComparingInt(r -> r.principle() == null ? 0 : r.principle().length()));

        Set<String> actionIdsSeen = new LinkedHashSet<>();
        Set<String> limitationsSeen = new LinkedHashSet<>();
        for (Rule rule : matched) {
            if (rule.recommendedActionIds() != null) {
                actionIdsSeen.addAll(rule.recommendedActionIds());
            }
            if (rule.limitations() != null) {
                limitationsSeen.addAll(rule.limitations());
            }
        }

        List<ManeuverAction> actions = new ArrayList<>();
        for (String id : actionIdsSeen) {
            ManeuverAction action = actionIndex.get(id);
            if (action != null) {
                actions.add(action);
            }
        }

        return new RegulatoryContext(
                Collections.unmodifiableList(matched),
                Collections.unmodifiableList(actions),
                Collections.unmodifiableList(new ArrayList<>(limitationsSeen))
        );
    }
}
