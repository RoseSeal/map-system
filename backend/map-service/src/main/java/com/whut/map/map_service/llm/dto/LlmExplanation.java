package com.whut.map.map_service.llm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmExplanation {
    private String source;
    private String provider;
    private String targetId;
    private String riskLevel;
    private String text;
    private String timestamp;
}
