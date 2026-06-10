package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QueryHistoricalCaseGraphToolContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(HistoricalCaseQueryPort.class, () -> mock(HistoricalCaseQueryPort.class))
            .withUserConfiguration(QueryHistoricalCaseGraphTool.class);

    @Test
    void loadsToolWhenEnabled() {
        contextRunner
                .withPropertyValues("llm.case-graph.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(QueryHistoricalCaseGraphTool.class));
    }

    @Test
    void doesNotLoadToolWhenDisabled() {
        contextRunner
                .withPropertyValues("llm.case-graph.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryHistoricalCaseGraphTool.class));
    }

    @Test
    void doesNotLoadToolWhenPropertyIsMissing() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(QueryHistoricalCaseGraphTool.class));
    }
}
