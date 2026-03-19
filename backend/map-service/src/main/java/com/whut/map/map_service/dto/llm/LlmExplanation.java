package com.whut.map.map_service.dto.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmExplanation {
    private String source;
    private String text;
}
