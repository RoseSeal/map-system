package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RiskContextFormatter {

    private final LlmProperties llmProperties;

    public String formatSummary(LlmRiskContext context, Instant updatedAt) {
        if (context == null || context.getOwnShip() == null) {
            return null;
        }

        List<LlmRiskTargetContext> allTargets = context.getTargets() == null ? List.of() : context.getTargets();
        List<LlmRiskTargetContext> visibleTargets = allTargets.stream()
                .filter(this::isVisibleTarget)
                .sorted(targetComparator())
                .limit(Math.max(1, llmProperties.getChatContextMaxTargets()))
                .toList();

        int totalTargets = allTargets.size();
        int hiddenTargets = Math.max(0, totalTargets - visibleTargets.size());

        StringBuilder builder = new StringBuilder();
        builder.append("【当前态势】更新时间: ")
                .append(updatedAt == null ? "未知" : updatedAt)
                .append('\n');
        appendOwnShip(builder, context.getOwnShip());
        builder.append("-----").append('\n');
        if (totalTargets == 0) {
            builder.append("当前未追踪到目标船。").append('\n');
        } else if (visibleTargets.isEmpty()) {
            builder.append("当前无非SAFE目标船，全部目标船风险等级均为 SAFE。").append('\n');
        } else {
            for (LlmRiskTargetContext target : visibleTargets) {
                appendTarget(builder, target);
            }
        }
        builder.append("共追踪 ")
                .append(totalTargets)
                .append(" 艘目标船，展示 ")
                .append(visibleTargets.size())
                .append(" 艘，未展示 ")
                .append(hiddenTargets)
                .append(" 艘。");
        return builder.toString();
    }

    public String formatSelectedTargets(LlmRiskContext context, List<String> targetIds, Instant updatedAt) {
        if (context == null || context.getOwnShip() == null || context.getTargets() == null
                || targetIds == null || targetIds.isEmpty()) {
            return null;
        }

        Map<String, LlmRiskTargetContext> targetById = new LinkedHashMap<>();
        for (LlmRiskTargetContext target : context.getTargets()) {
            if (target != null && StringUtils.hasText(target.getTargetId())) {
                targetById.put(target.getTargetId(), target);
            }
        }

        List<String> distinctTargetIds = targetIds.stream()
                .distinct()
                .toList();

        List<LlmRiskTargetContext> matched = distinctTargetIds.stream()
                .filter(targetById::containsKey)
                .map(targetById::get)
                .toList();

        if (matched.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("【选中目标详情】更新时间: ")
                .append(updatedAt == null ? "未知" : updatedAt)
                .append('\n');
        appendOwnShip(builder, context.getOwnShip());
        builder.append("-----").append('\n');
        for (LlmRiskTargetContext target : matched) {
            appendTargetDetail(builder, target);
        }
        builder.append("共选中 ")
                .append(distinctTargetIds.size())
                .append(" 艘，匹配 ")
                .append(matched.size())
                .append(" 艘。");
        return builder.toString();
    }

    public String formatConsolidated(LlmRiskContext context, List<String> selectedTargetIds, Instant updatedAt) {
        if (context == null || context.getOwnShip() == null) {
            return null;
        }

        List<LlmRiskTargetContext> allTargets = context.getTargets() == null ? List.of() : context.getTargets();
        List<LlmRiskTargetContext> summaryTargets = allTargets.stream()
                .filter(this::isVisibleTarget)
                .sorted(targetComparator())
                .limit(Math.max(1, llmProperties.getChatContextMaxTargets()))
                .toList();

        Map<String, LlmRiskTargetContext> targetById = targetById(allTargets);
        List<String> distinctSelectedIds = distinctSelectedIds(selectedTargetIds);
        Set<String> selectedSet = new LinkedHashSet<>(distinctSelectedIds);

        LinkedHashMap<String, LlmRiskTargetContext> orderedTargets = new LinkedHashMap<>();
        for (LlmRiskTargetContext target : summaryTargets) {
            orderedTargets.put(target.getTargetId(), target);
        }

        List<LlmRiskTargetContext> extraSelectedTargets = distinctSelectedIds.stream()
                .filter(targetById::containsKey)
                .filter(id -> !orderedTargets.containsKey(id))
                .map(targetById::get)
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("【当前态势】更新时间: ")
                .append(updatedAt == null ? "未知" : updatedAt)
                .append('\n');
        appendOwnShip(builder, context.getOwnShip());
        builder.append("-----").append('\n');

        if (allTargets.isEmpty()) {
            builder.append("当前未追踪到目标船。").append('\n');
        } else if (summaryTargets.isEmpty()) {
            builder.append("当前无非SAFE目标船，全部目标船风险等级均为 SAFE。").append('\n');
        } else {
            for (LlmRiskTargetContext target : summaryTargets) {
                if (selectedSet.contains(target.getTargetId())) {
                    appendTargetDetail(builder, target);
                } else {
                    appendTarget(builder, target);
                }
            }
        }

        if (!extraSelectedTargets.isEmpty()) {
            builder.append("-----").append('\n');
            builder.append("【用户关注目标】").append('\n');
            for (LlmRiskTargetContext target : extraSelectedTargets) {
                appendTargetDetail(builder, target);
            }
        }

        int injectedTargets = summaryTargets.size() + extraSelectedTargets.size();
        builder.append("共追踪 ")
                .append(allTargets.size())
                .append(" 艘目标船，当前注入 ")
                .append(injectedTargets)
                .append(" 艘，未注入 ")
                .append(Math.max(0, allTargets.size() - injectedTargets))
                .append(" 艘。");
        return builder.toString();
    }

    private void appendTargetDetail(StringBuilder builder, LlmRiskTargetContext target) {
        builder.append("目标船 ")
                .append(defaultText(target.getTargetId()))
                .append(": 风险等级 ")
                .append(target.getRiskLevel())
                .append(", 现距 ")
                .append(formatDistanceNm(target.getCurrentDistanceNm()))
                .append("海里, DCPA ")
                .append(formatDecimal(target.getDcpaNm(), 2))
                .append("海里, TCPA ")
                .append(formatDecimal(target.getTcpaSec(), 0))
                .append("秒, ")
                .append(target.isApproaching() ? "正在接近" : "未接近")
                .append(", 位置: (")
                .append(formatDecimal(target.getLongitude(), 4))
                .append(", ")
                .append(formatDecimal(target.getLatitude(), 4))
                .append("), 航速: ")
                .append(formatDecimal(target.getSpeedKn(), 1))
                .append("节, 航向: ")
                .append(formatDecimal(target.getCourseDeg(), 1))
                .append("°");
        if (StringUtils.hasText(target.getRuleExplanation())) {
            builder.append(", 说明: ").append(target.getRuleExplanation());
        }
        builder.append('\n');
    }

    private void appendOwnShip(StringBuilder builder, LlmRiskOwnShipContext ownShip) {
        builder.append("本船 ID: ")
                .append(defaultText(ownShip.getId()))
                .append(", 位置: (")
                .append(formatDecimal(ownShip.getLongitude(), 4))
                .append(", ")
                .append(formatDecimal(ownShip.getLatitude(), 4))
                .append("), 航速: ")
                .append(formatDecimal(ownShip.getSog(), 1))
                .append("节, 航向: ")
                .append(formatDecimal(ownShip.getCog(), 1))
                .append("°")
                .append('\n');
    }

    private void appendTarget(StringBuilder builder, LlmRiskTargetContext target) {
        builder.append("目标船 ")
                .append(defaultText(target.getTargetId()))
                .append(": 风险等级 ")
                .append(target.getRiskLevel())
                .append(", 现距 ")
                .append(formatDistanceNm(target.getCurrentDistanceNm()))
                .append("海里, DCPA ")
                .append(formatDecimal(target.getDcpaNm(), 2))
                .append("海里, TCPA ")
                .append(formatDecimal(target.getTcpaSec(), 0))
                .append("秒, ")
                .append(target.isApproaching() ? "正在接近" : "未接近")
                .append('\n');
    }

    private boolean isVisibleTarget(LlmRiskTargetContext target) {
        if (target == null || !StringUtils.hasText(target.getTargetId()) || !StringUtils.hasText(target.getRiskLevel())) {
            return false;
        }
        return riskLevelOrder(target.getRiskLevel()) > riskLevelOrder(RiskConstants.SAFE);
    }

    private Comparator<LlmRiskTargetContext> targetComparator() {
        return Comparator
                .comparingInt((LlmRiskTargetContext target) -> riskLevelOrder(target.getRiskLevel()))
                .reversed()
                .thenComparingDouble(LlmRiskTargetContext::getCurrentDistanceNm);
    }

    private int riskLevelOrder(String riskLevel) {
        if (RiskConstants.ALARM.equals(riskLevel)) {
            return 4;
        }
        if (RiskConstants.WARNING.equals(riskLevel)) {
            return 3;
        }
        if (RiskConstants.CAUTION.equals(riskLevel)) {
            return 2;
        }
        if (RiskConstants.SAFE.equals(riskLevel)) {
            return 1;
        }
        return 0;
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }

    private Map<String, LlmRiskTargetContext> targetById(List<LlmRiskTargetContext> targets) {
        Map<String, LlmRiskTargetContext> targetById = new LinkedHashMap<>();
        for (LlmRiskTargetContext target : targets) {
            if (target != null && StringUtils.hasText(target.getTargetId())) {
                targetById.put(target.getTargetId(), target);
            }
        }
        return targetById;
    }

    private List<String> distinctSelectedIds(List<String> selectedTargetIds) {
        if (selectedTargetIds == null || selectedTargetIds.isEmpty()) {
            return List.of();
        }
        return selectedTargetIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String formatDecimal(double value, int scale) {
        return String.format("%." + scale + "f", value);
    }

    private String formatDistanceNm(Double value) {
        return value == null ? "未知" : formatDecimal(value, 2);
    }
}
