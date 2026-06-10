package com.whut.map.map_service.llm.agent.casegraph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.whut.map.map_service.llm.config.CaseGraphProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class LightRagCaseGraphAdapter implements HistoricalCaseQueryPort {

    private static final String RETRIEVE_PATH = "/retrieve";

    private final RestTemplate restTemplate;
    private final CaseGraphProperties properties;
    private final ObjectMapper objectMapper;

    public LightRagCaseGraphAdapter(
            RestTemplate restTemplate,
            CaseGraphProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public HistoricalCaseResult findSimilarCases(HistoricalCaseQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        boolean hasStructuredFeatures = hasStructuredFeatures(query);
        if (!StringUtils.hasText(query.queryText()) && !hasStructuredFeatures) {
            throw new IllegalArgumentException("queryText and structured features must not both be empty");
        }

        CaseQueryMode effectiveMode = query.mode() == null ? properties.getDefaultMode() : query.mode();
        int effectiveTopK = query.topK() == null ? properties.getDefaultTopK() : query.topK();
        RetrieveRequest request = new RetrieveRequest(
                normalizeText(query.queryText()),
                hasStructuredFeatures ? toSituation(query) : null,
                effectiveMode.name().toLowerCase(Locale.ROOT),
                effectiveTopK
        );

        RetrieveResponse response = postRetrieve(request);
        return toDomain(response);
    }

    private RetrieveResponse postRetrieve(RetrieveRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String requestBody = objectMapper.writeValueAsString(request);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    buildRetrieveUrl(),
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            if (response.getBody() == null || response.getBody().isBlank()) {
                throw new CaseGraphUnavailableException("Case graph response body is empty");
            }
            return objectMapper.readValue(response.getBody(), RetrieveResponse.class);
        } catch (HttpStatusCodeException e) {
            String error = extractError(e.getResponseBodyAsString());
            throw new CaseGraphUnavailableException(
                    "Case graph request failed with status " + e.getStatusCode().value() + ": " + error,
                    e
            );
        } catch (RestClientException e) {
            throw new CaseGraphUnavailableException("Case graph request failed", e);
        } catch (JsonProcessingException e) {
            throw new CaseGraphUnavailableException("Case graph JSON mapping failed", e);
        }
    }

    private HistoricalCaseResult toDomain(RetrieveResponse response) {
        if (response == null || !StringUtils.hasText(response.mode())) {
            throw new CaseGraphUnavailableException("Case graph response is missing mode");
        }

        CaseQueryMode mode;
        try {
            mode = CaseQueryMode.valueOf(response.mode().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CaseGraphUnavailableException("Case graph response has unsupported mode: " + response.mode(), e);
        }

        List<HistoricalCase> cases = response.cases() == null
                ? List.of()
                : response.cases().stream()
                        .filter(Objects::nonNull)
                        .map(this::toDomainCase)
                        .toList();

        return new HistoricalCaseResult(
                mode,
                response.queryEffective(),
                cases,
                response.answer(),
                toMetrics(response.metrics())
        );
    }

    private HistoricalCase toDomainCase(CaseDto dto) {
        return new HistoricalCase(
                dto.caseId(),
                dto.title(),
                dto.relevance(),
                dto.waterArea(),
                dto.visibility(),
                dto.ownShipRole(),
                dto.encounterType(),
                dto.riskLevel(),
                dto.targetSummary(),
                dto.colregsRules() == null ? List.of() : List.copyOf(dto.colregsRules()),
                dto.outcome(),
                dto.actionDigest(),
                dto.lesson()
        );
    }

    private CaseRetrievalMetrics toMetrics(JsonNode metrics) {
        if (metrics == null || metrics.isNull()) {
            return null;
        }
        JsonNode tokens = metrics.get("tokens");
        return new CaseRetrievalMetrics(
                metrics.path("latency_ms").asLong(0L),
                integerOrNull(tokens, "prompt"),
                integerOrNull(tokens, "completion")
        );
    }

    private Integer integerOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).intValue();
    }

    private Situation toSituation(HistoricalCaseQuery query) {
        return new Situation(
                enumName(query.ownShipRole()),
                enumName(query.encounterType()),
                normalizeText(query.waterArea()),
                enumName(query.visibilityCondition()),
                enumName(query.riskLevel()),
                normalizeText(query.ownShipType()),
                normalizeText(query.targetShipType()),
                query.targetCount() > 0 ? query.targetCount() : null
        );
    }

    private boolean hasStructuredFeatures(HistoricalCaseQuery query) {
        return query.encounterType() != null
                || query.ownShipRole() != null
                || query.visibilityCondition() != null
                || query.riskLevel() != null
                || StringUtils.hasText(query.waterArea())
                || StringUtils.hasText(query.ownShipType())
                || StringUtils.hasText(query.targetShipType())
                || query.targetCount() > 0;
    }

    private String buildRetrieveUrl() {
        String baseUrl = properties.getUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new CaseGraphUnavailableException("llm.case-graph.url must not be blank");
        }
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + RETRIEVE_PATH
                : baseUrl + RETRIEVE_PATH;
    }

    private String extractError(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "empty error response";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("error")) {
                return root.get("error").asText();
            }
        } catch (JsonProcessingException ignored) {
            // Preserve the raw response below when the sidecar does not return JSON.
        }
        return responseBody;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    static record RetrieveRequest(
            String query,
            Situation situation,
            String mode,
            Integer topK
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    static record Situation(
            String ownShipRole,
            String encounterType,
            String waterArea,
            String visibility,
            String riskLevel,
            String ownShipType,
            String targetShipType,
            Integer targetCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    static record RetrieveResponse(
            String mode,
            String queryEffective,
            List<CaseDto> cases,
            String answer,
            JsonNode metrics
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    static record CaseDto(
            String caseId,
            String title,
            double relevance,
            String waterArea,
            String visibility,
            String ownShipRole,
            String encounterType,
            String riskLevel,
            String targetSummary,
            List<String> colregsRules,
            String outcome,
            String actionDigest,
            String lesson
    ) {}
}
