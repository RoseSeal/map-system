package com.whut.map.map_service.llm.agent.casegraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.graph.VisibilityCondition;
import com.whut.map.map_service.llm.config.CaseGraphProperties;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LightRagCaseGraphAdapterTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LightRagCaseGraphAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        CaseGraphProperties properties = new CaseGraphProperties();
        properties.setUrl("http://case-graph.test/");
        properties.setDefaultMode(CaseQueryMode.LOCAL);
        properties.setDefaultTopK(5);
        adapter = new LightRagCaseGraphAdapter(restTemplate, properties, new ObjectMapper());
    }

    @Test
    void mapsStructuredQueryAndResponse() {
        server.expect(requestTo("http://case-graph.test/retrieve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "situation": {
                            "own_ship_role": "GIVE_WAY",
                            "encounter_type": "CROSSING",
                            "water_area": "限制水域",
                            "visibility": "RESTRICTED_VISIBILITY",
                            "risk_level": "WARNING",
                            "own_ship_type": "CARGO",
                            "target_ship_type": "TANKER",
                            "target_count": 2
                          },
                          "mode": "local",
                          "top_k": 5
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "mode": "local",
                          "query_effective": "态势特征：交叉相遇",
                          "cases": [
                            {
                              "case_id": "H-02",
                              "title": "限制水域交叉相遇让路船减速右转",
                              "relevance": 0.94,
                              "water_area": "限制水域",
                              "visibility": "RESTRICTED_VISIBILITY",
                              "own_ship_role": "GIVE_WAY",
                              "encounter_type": "CROSSING",
                              "risk_level": "WARNING",
                              "target_summary": "1 艘货船交叉接近",
                              "colregs_rules": ["Rule 15", "Rule 16"],
                              "outcome": "SAFE",
                              "action_digest": "减速并向右转向",
                              "lesson": "及早避让"
                            }
                          ],
                          "answer": "相似案例表明应及早采取明显行动。",
                          "metrics": {
                            "latency_ms": 740,
                            "tokens": {"prompt": 1820, "completion": 240},
                            "cases_indexed": 12
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        HistoricalCaseResult result = adapter.findSimilarCases(structuredQuery());

        assertThat(result.mode()).isEqualTo(CaseQueryMode.LOCAL);
        assertThat(result.queryEffective()).contains("交叉相遇");
        assertThat(result.cases()).hasSize(1);
        HistoricalCase historicalCase = result.cases().getFirst();
        assertThat(historicalCase.caseId()).isEqualTo("H-02");
        assertThat(historicalCase.relevance()).isEqualTo(0.94);
        assertThat(historicalCase.colregsRules()).containsExactly("Rule 15", "Rule 16");
        assertThat(result.metrics()).isEqualTo(new CaseRetrievalMetrics(740L, 1820, 240));
        server.verify();
    }

    @Test
    void mapsEmptyCasesAsNormalResult() {
        server.expect(requestTo("http://case-graph.test/retrieve"))
                .andRespond(withSuccess("""
                        {
                          "mode": "hybrid",
                          "query_effective": "未命中查询",
                          "cases": [],
                          "answer": "未找到相似案例。",
                          "metrics": {"latency_ms": 12, "tokens": {"prompt": 0, "completion": 0}}
                        }
                        """, MediaType.APPLICATION_JSON));

        HistoricalCaseResult result = adapter.findSimilarCases(new HistoricalCaseQuery(
                null, null, null, null,
                null, null, null, 0,
                "未命中查询", CaseQueryMode.HYBRID, 3
        ));

        assertThat(result.mode()).isEqualTo(CaseQueryMode.HYBRID);
        assertThat(result.cases()).isEmpty();
        server.verify();
    }

    @Test
    void wrapsSidecar503AsTypedException() {
        server.expect(requestTo("http://case-graph.test/retrieve"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"index_missing\"}"));

        assertThatThrownBy(() -> adapter.findSimilarCases(structuredQuery()))
                .isInstanceOf(CaseGraphUnavailableException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("index_missing");
        server.verify();
    }

    @Test
    void rejectsEmptyQueryBeforeSendingRequest() {
        HistoricalCaseQuery query = new HistoricalCaseQuery(
                null, null, null, null,
                " ", null, null, 0,
                " ", null, null
        );

        assertThatThrownBy(() -> adapter.findSimilarCases(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not both be empty");
        server.verify();
    }

    @Test
    void wrapsBlankBaseUrlAsTypedException() {
        CaseGraphProperties properties = new CaseGraphProperties();
        properties.setUrl(" ");
        LightRagCaseGraphAdapter blankUrlAdapter =
                new LightRagCaseGraphAdapter(restTemplate, properties, new ObjectMapper());

        assertThatThrownBy(() -> blankUrlAdapter.findSimilarCases(structuredQuery()))
                .isInstanceOf(CaseGraphUnavailableException.class)
                .hasMessage("llm.case-graph.url must not be blank");
        server.verify();
    }

    private HistoricalCaseQuery structuredQuery() {
        return new HistoricalCaseQuery(
                EncounterType.CROSSING,
                OwnShipRole.GIVE_WAY,
                VisibilityCondition.RESTRICTED_VISIBILITY,
                RiskLevel.WARNING,
                "限制水域",
                "CARGO",
                "TANKER",
                2,
                null,
                null,
                null
        );
    }
}
