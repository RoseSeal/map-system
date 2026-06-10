package com.whut.map.map_service.llm.config;

import com.whut.map.map_service.llm.agent.casegraph.CaseQueryMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm.case-graph")
public class CaseGraphProperties {

    private boolean enabled = false;
    private String url = "http://127.0.0.1:8100";
    private long timeoutMs = 10000L;
    private CaseQueryMode defaultMode = CaseQueryMode.LOCAL;
    private int defaultTopK = 5;
}
