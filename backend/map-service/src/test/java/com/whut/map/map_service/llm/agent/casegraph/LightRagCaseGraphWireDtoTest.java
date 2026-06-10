package com.whut.map.map_service.llm.agent.casegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagCaseGraphWireDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesRequestAsSnakeCaseWithLowercaseMode() throws Exception {
        LightRagCaseGraphAdapter.RetrieveRequest request =
                new LightRagCaseGraphAdapter.RetrieveRequest(
                        "交叉相遇",
                        new LightRagCaseGraphAdapter.Situation(
                                "GIVE_WAY",
                                "CROSSING",
                                "限制水域",
                                "RESTRICTED_VISIBILITY",
                                "WARNING",
                                "CARGO",
                                "TANKER",
                                2
                        ),
                        CaseQueryMode.LOCAL.name().toLowerCase(),
                        4
                );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.path("top_k").asInt()).isEqualTo(4);
        assertThat(json.path("mode").asText()).isEqualTo("local");
        assertThat(json.path("situation").path("own_ship_role").asText()).isEqualTo("GIVE_WAY");
        assertThat(json.path("situation").path("target_ship_type").asText()).isEqualTo("TANKER");
    }

    @Test
    void omitsNullRequestAndSituationFields() throws Exception {
        LightRagCaseGraphAdapter.RetrieveRequest structuredOnly =
                new LightRagCaseGraphAdapter.RetrieveRequest(
                        null,
                        new LightRagCaseGraphAdapter.Situation(
                                "GIVE_WAY",
                                "CROSSING",
                                null,
                                "OPEN_VISIBILITY",
                                null,
                                null,
                                null,
                                1
                        ),
                        "local",
                        5
                );
        LightRagCaseGraphAdapter.RetrieveRequest textOnly =
                new LightRagCaseGraphAdapter.RetrieveRequest(
                        "交叉相遇",
                        null,
                        "local",
                        5
                );

        JsonNode structuredJson = objectMapper.readTree(objectMapper.writeValueAsString(structuredOnly));
        JsonNode textJson = objectMapper.readTree(objectMapper.writeValueAsString(textOnly));

        assertThat(structuredJson.has("query")).isFalse();
        assertThat(structuredJson.path("situation").has("water_area")).isFalse();
        assertThat(structuredJson.path("situation").has("risk_level")).isFalse();
        assertThat(structuredJson.path("situation").has("own_ship_type")).isFalse();
        assertThat(textJson.has("situation")).isFalse();
    }

    @Test
    void deserializesSnakeCaseResponse() throws Exception {
        LightRagCaseGraphAdapter.RetrieveResponse response = objectMapper.readValue("""
                {
                  "mode": "global",
                  "query_effective": "全局案例查询",
                  "cases": [
                    {
                      "case_id": "H-03",
                      "title": "受限能见度交叉相遇",
                      "relevance": 0.88,
                      "colregs_rules": ["Rule 19"],
                      "action_digest": "保守减速"
                    }
                  ],
                  "answer": "全局检索结果",
                  "metrics": {"latency_ms": 100}
                }
                """, LightRagCaseGraphAdapter.RetrieveResponse.class);

        assertThat(response.mode()).isEqualTo("global");
        assertThat(CaseQueryMode.valueOf(response.mode().toUpperCase())).isEqualTo(CaseQueryMode.GLOBAL);
        assertThat(response.queryEffective()).isEqualTo("全局案例查询");
        assertThat(response.cases())
                .extracting(LightRagCaseGraphAdapter.CaseDto::caseId)
                .containsExactly("H-03");
        assertThat(response.cases().getFirst().colregsRules()).isEqualTo(List.of("Rule 19"));
        assertThat(response.cases().getFirst().actionDigest()).isEqualTo("保守减速");
    }
}
