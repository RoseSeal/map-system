package com.whut.map.map_service.llm.agent.casegraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.config.CaseGraphProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CaseGraphConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CaseGraphProperties.class, CaseGraphProperties::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(CaseGraphConfig.class);

    @Test
    void loadsPortWhenEnabled() {
        contextRunner
                .withPropertyValues("llm.case-graph.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(HistoricalCaseQueryPort.class));
    }

    @Test
    void supportsTimeoutLargerThanIntegerMilliseconds() {
        contextRunner
                .withPropertyValues(
                        "llm.case-graph.enabled=true",
                        "llm.case-graph.timeout-ms=2147483648"
                )
                .run(context -> assertThat(context).hasSingleBean(HistoricalCaseQueryPort.class));
    }

    @Test
    void enabledConfigFailsFastWhenUrlIsBlank() {
        new ApplicationContextRunner()
                .withBean(CaseGraphProperties.class, () -> {
                    CaseGraphProperties properties = new CaseGraphProperties();
                    properties.setUrl(" ");
                    return properties;
                })
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(CaseGraphConfig.class)
                .withPropertyValues("llm.case-graph.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("llm.case-graph.url must not be blank");
                });
    }

    @Test
    void doesNotLoadPortWhenDisabled() {
        contextRunner
                .withPropertyValues("llm.case-graph.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HistoricalCaseQueryPort.class));
    }

    @Test
    void doesNotLoadPortWhenPropertyIsMissing() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(HistoricalCaseQueryPort.class));
    }
}
