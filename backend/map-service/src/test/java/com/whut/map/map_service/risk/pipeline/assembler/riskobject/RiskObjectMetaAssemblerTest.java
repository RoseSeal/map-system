package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskObjectMetaAssemblerTest {

    @Test
    void buildGovernanceUsesConfiguredModeAndProvidedTrustFactor() {
        RiskObjectMetaProperties properties = new RiskObjectMetaProperties();
        properties.setGovernanceMode("manual");
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(properties);

        Map<String, Object> governance = assembler.buildGovernance(0.42);

        assertThat(governance).containsEntry("mode", "manual");
        assertThat(governance).containsEntry("trust_factor", 0.42);
    }
}
