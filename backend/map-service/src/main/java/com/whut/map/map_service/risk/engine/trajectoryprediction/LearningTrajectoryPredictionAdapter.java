package com.whut.map.map_service.risk.engine.trajectoryprediction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.whut.map.map_service.risk.config.TrajectoryPredictionProperties;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.AisProtocolConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
public class LearningTrajectoryPredictionAdapter implements TrajectoryPredictionPort {

    private static final String PREDICT_PATH = "/predict";

    private final RestTemplate restTemplate;
    private final TrajectoryPredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final CvPredictionEngine fallback;
    private final Clock clock;
    private final Map<String, CachedPrediction> cache = new ConcurrentHashMap<>();
    private final Map<String, List<RawSample>> rawBuffers = new ConcurrentHashMap<>();

    private int consecutiveFailures;
    private Instant circuitOpenUntil;

    public LearningTrajectoryPredictionAdapter(
            RestTemplate restTemplate,
            TrajectoryPredictionProperties properties,
            ObjectMapper objectMapper,
            CvPredictionEngine fallback
    ) {
        this(restTemplate, properties, objectMapper, fallback, Clock.systemUTC());
    }

    LearningTrajectoryPredictionAdapter(
            RestTemplate restTemplate,
            TrajectoryPredictionProperties properties,
            ObjectMapper objectMapper,
            CvPredictionEngine fallback,
            Clock clock
    ) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CvPredictionResult consume(ShipStatus message, List<ShipStatus> history) {
        if (message == null || message.getId() == null) {
            return fallback.consume(message, history);
        }
        return consumeAll(null, List.of(message), id -> history).get(message.getId());
    }

    @Override
    public Map<String, CvPredictionResult> consumeAll(
            String ownShipId,
            Collection<ShipStatus> allShips,
            Function<String, List<ShipStatus>> historyProvider
    ) {
        Map<String, CvPredictionResult> results = new LinkedHashMap<>();
        if (allShips == null || allShips.isEmpty()) {
            return results;
        }
        if (isCircuitOpen()) {
            return fallbackAll(ownShipId, allShips, historyProvider);
        }

        List<TargetWindowRequest> pending = new ArrayList<>();
        Map<String, ShipStatus> pendingShips = new HashMap<>();
        Map<String, List<ShipStatus>> pendingHistories = new HashMap<>();
        Map<String, String> pendingSignatures = new HashMap<>();

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
                continue;
            }
            List<ShipStatus> history = historyProvider == null ? List.of() : historyProvider.apply(ship.getId());
            List<RawPoint> window = buildWindow(ship, history);
            if (window.size() < properties.getLearnedWindowSize()) {
                results.put(ship.getId(), fallback.consume(ship, history));
                continue;
            }

            String signature = signatureOf(window);
            CachedPrediction cached = cache.get(ship.getId());
            if (cached != null && cached.signature().equals(signature)) {
                results.put(ship.getId(), cached.result());
                continue;
            }

            pending.add(new TargetWindowRequest(ship.getId(), window));
            pendingShips.put(ship.getId(), ship);
            pendingHistories.put(ship.getId(), history);
            pendingSignatures.put(ship.getId(), signature);
        }

        if (pending.isEmpty()) {
            return results;
        }

        try {
            PredictionResponse response = postPredict(new PredictionRequest(pending));
            recordSuccess();
            Map<String, TargetPredictionResponse> byTarget = responseByTarget(response);
            for (TargetWindowRequest request : pending) {
                ShipStatus ship = pendingShips.get(request.targetId());
                TargetPredictionResponse targetResponse = byTarget.get(request.targetId());
                if (targetResponse == null || targetResponse.points() == null || targetResponse.points().isEmpty()) {
                    results.put(request.targetId(), fallback.consume(ship, pendingHistories.get(request.targetId())));
                    continue;
                }
                CvPredictionResult result = toDomain(ship, targetResponse);
                cache.put(request.targetId(), new CachedPrediction(pendingSignatures.get(request.targetId()), result));
                results.put(request.targetId(), result);
            }
        } catch (LearningTrajectoryPredictionUnavailableException e) {
            recordFailure(e);
            for (TargetWindowRequest request : pending) {
                ShipStatus ship = pendingShips.get(request.targetId());
                results.put(request.targetId(), fallback.consume(ship, pendingHistories.get(request.targetId())));
            }
        }
        return results;
    }

    private PredictionResponse postPredict(PredictionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String requestBody = objectMapper.writeValueAsString(request);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    buildPredictUrl(),
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            if (response.getBody() == null || response.getBody().isBlank()) {
                throw new LearningTrajectoryPredictionUnavailableException("Trajectory sidecar response body is empty");
            }
            return objectMapper.readValue(response.getBody(), PredictionResponse.class);
        } catch (HttpStatusCodeException e) {
            throw new LearningTrajectoryPredictionUnavailableException(
                    "Trajectory sidecar request failed with status "
                            + e.getStatusCode().value()
                            + ": "
                            + extractError(e.getResponseBodyAsString()),
                    e
            );
        } catch (RestClientException e) {
            throw new LearningTrajectoryPredictionUnavailableException("Trajectory sidecar request failed", e);
        } catch (JsonProcessingException e) {
            throw new LearningTrajectoryPredictionUnavailableException("Trajectory sidecar JSON mapping failed", e);
        }
    }

    private Map<String, CvPredictionResult> fallbackAll(
            String ownShipId,
            Collection<ShipStatus> allShips,
            Function<String, List<ShipStatus>> historyProvider
    ) {
        Map<String, CvPredictionResult> results = new LinkedHashMap<>();
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
                continue;
            }
            List<ShipStatus> history = historyProvider == null ? List.of() : historyProvider.apply(ship.getId());
            seedBufferIfEmpty(ship.getId(), history);
            appendToBuffer(ship.getId(), ship);
            results.put(ship.getId(), fallback.consume(ship, history));
        }
        return results;
    }

    private List<RawPoint> buildWindow(ShipStatus current, List<ShipStatus> history) {
        if (current == null || current.getId() == null) {
            return List.of();
        }

        seedBufferIfEmpty(current.getId(), history);
        appendToBuffer(current.getId(), current);
        List<RawSample> samples = rawBuffers.getOrDefault(current.getId(), List.of());

        List<RawSample> selected = new ArrayList<>();
        long sampleInterval = properties.getLearnedSampleIntervalSeconds();
        Instant lastSelected = null;
        for (int i = samples.size() - 1; i >= 0; i -= 1) {
            RawSample point = samples.get(i);
            Instant ts = point.timestamp();
            if (lastSelected == null
                    || sampleInterval <= 0
                    || Duration.between(ts, lastSelected).getSeconds() >= sampleInterval) {
                selected.add(point);
                lastSelected = ts;
            }
            if (selected.size() >= properties.getLearnedWindowSize()) {
                break;
            }
        }
        selected.sort(Comparator.comparing(RawSample::timestamp));

        return selected.stream()
                .map(RawSample::point)
                .toList();
    }

    private void seedBufferIfEmpty(String targetId, List<ShipStatus> history) {
        if (history == null || history.isEmpty() || rawBuffers.containsKey(targetId)) {
            return;
        }
        for (ShipStatus point : history) {
            appendToBuffer(targetId, point);
        }
    }

    private void appendToBuffer(String targetId, ShipStatus point) {
        RawSample sample = toRawSample(point);
        if (sample == null) {
            return;
        }
        rawBuffers.compute(targetId, (id, existing) -> {
            Map<Instant, RawSample> byTime = new LinkedHashMap<>();
            if (existing != null) {
                for (RawSample item : existing) {
                    byTime.put(item.timestamp(), item);
                }
            }
            byTime.put(sample.timestamp(), sample);
            List<RawSample> sorted = byTime.values().stream()
                    .sorted(Comparator.comparing(RawSample::timestamp))
                    .toList();
            return pruneBuffer(sorted);
        });
    }

    private List<RawSample> pruneBuffer(List<RawSample> sorted) {
        if (sorted.isEmpty()) {
            return List.of();
        }
        long sampleInterval = Math.max(1, properties.getLearnedSampleIntervalSeconds());
        long retainSeconds = sampleInterval * Math.max(1, properties.getLearnedWindowSize() + 2L);
        Instant cutoff = sorted.getLast().timestamp().minusSeconds(retainSeconds);
        List<RawSample> retained = sorted.stream()
                .filter(point -> !point.timestamp().isBefore(cutoff))
                .toList();
        int maxPoints = Math.max(properties.getLearnedWindowSize(), properties.getLearnedRawBufferMaxPoints());
        if (retained.size() <= maxPoints) {
            return List.copyOf(retained);
        }
        return List.copyOf(retained.subList(retained.size() - maxPoints, retained.size()));
    }

    private RawSample toRawSample(ShipStatus point) {
        if (!isValidPoint(point)) {
            return null;
        }
        return new RawSample(
                point.getMsgTime().toInstant(),
                new RawPoint(
                        point.getMsgTime().toString(),
                        point.getLatitude(),
                        point.getLongitude(),
                        point.getSog(),
                        point.getCog()
                )
        );
    }

    private boolean isValidPoint(ShipStatus point) {
        return point != null
                && point.getMsgTime() != null
                && Double.isFinite(point.getLatitude())
                && Double.isFinite(point.getLongitude())
                && AisProtocolConstants.isValidSog(point.getSog())
                && AisProtocolConstants.isValidCog(point.getCog());
    }

    private String signatureOf(List<RawPoint> window) {
        StringBuilder builder = new StringBuilder();
        for (RawPoint point : window) {
            builder.append(point.msgTime()).append('|')
                    .append(point.latitude()).append('|')
                    .append(point.longitude()).append('|')
                    .append(point.sog()).append('|')
                    .append(point.cog()).append(';');
        }
        return builder.toString();
    }

    private Map<String, TargetPredictionResponse> responseByTarget(PredictionResponse response) {
        Map<String, TargetPredictionResponse> byTarget = new HashMap<>();
        if (response == null || response.predictions() == null) {
            return byTarget;
        }
        for (TargetPredictionResponse prediction : response.predictions()) {
            if (prediction != null && prediction.targetId() != null) {
                byTarget.put(prediction.targetId(), prediction);
            }
        }
        return byTarget;
    }

    private CvPredictionResult toDomain(ShipStatus ship, TargetPredictionResponse response) {
        List<CvPredictionResult.PredictedPoint> skeleton = response.points().stream()
                .filter(Objects::nonNull)
                .map(point -> CvPredictionResult.PredictedPoint.builder()
                        .latitude(point.latitude())
                        .longitude(point.longitude())
                        .offsetSeconds(point.offsetSeconds())
                        .build())
                .sorted(Comparator.comparingInt(CvPredictionResult.PredictedPoint::getOffsetSeconds))
                .toList();
        List<CvPredictionResult.PredictedPoint> points = densify(ship, skeleton);

        int horizon = points.stream()
                .mapToInt(CvPredictionResult.PredictedPoint::getOffsetSeconds)
                .max()
                .orElse(properties.getLearnedHorizonSeconds());

        return CvPredictionResult.builder()
                .targetId(response.targetId())
                .trajectory(points)
                .predictionTime(ship.getMsgTime() == null ? Instant.now(clock) : ship.getMsgTime().toInstant())
                .horizonSeconds(horizon)
                .predictionSource("GRU")
                .build();
    }

    private List<CvPredictionResult.PredictedPoint> densify(
            ShipStatus ship,
            List<CvPredictionResult.PredictedPoint> skeleton
    ) {
        if (ship == null || skeleton == null || skeleton.isEmpty()) {
            return List.of();
        }
        List<CvPredictionResult.PredictedPoint> anchors = new ArrayList<>();
        anchors.add(CvPredictionResult.PredictedPoint.builder()
                .latitude(ship.getLatitude())
                .longitude(ship.getLongitude())
                .offsetSeconds(0)
                .build());
        anchors.addAll(skeleton);

        Map<Integer, CvPredictionResult.PredictedPoint> points = new LinkedHashMap<>();
        int step = Math.max(1, properties.getStepSeconds());
        for (int i = 0; i < anchors.size() - 1; i += 1) {
            CvPredictionResult.PredictedPoint start = anchors.get(i);
            CvPredictionResult.PredictedPoint end = anchors.get(i + 1);
            if (end.getOffsetSeconds() <= start.getOffsetSeconds()) {
                continue;
            }
            int first = ((start.getOffsetSeconds() / step) + 1) * step;
            for (int offset = first; offset <= end.getOffsetSeconds(); offset += step) {
                points.put(offset, interpolate(start, end, offset));
            }
            points.put(end.getOffsetSeconds(), end);
        }
        return List.copyOf(points.values());
    }

    private CvPredictionResult.PredictedPoint interpolate(
            CvPredictionResult.PredictedPoint start,
            CvPredictionResult.PredictedPoint end,
            int offsetSeconds
    ) {
        double span = end.getOffsetSeconds() - start.getOffsetSeconds();
        double ratio = span <= 0 ? 1.0 : (offsetSeconds - start.getOffsetSeconds()) / span;
        return CvPredictionResult.PredictedPoint.builder()
                .latitude(start.getLatitude() + (end.getLatitude() - start.getLatitude()) * ratio)
                .longitude(start.getLongitude() + (end.getLongitude() - start.getLongitude()) * ratio)
                .offsetSeconds(offsetSeconds)
                .build();
    }

    private String buildPredictUrl() {
        String baseUrl = properties.getLearnedUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new LearningTrajectoryPredictionUnavailableException(
                    "engine.trajectory-prediction.learned-url must not be blank"
            );
        }
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + PREDICT_PATH
                : baseUrl + PREDICT_PATH;
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
            // Keep raw response below.
        }
        return responseBody;
    }

    private synchronized boolean isCircuitOpen() {
        Instant now = Instant.now(clock);
        return circuitOpenUntil != null && now.isBefore(circuitOpenUntil);
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
        circuitOpenUntil = null;
    }

    private synchronized void recordFailure(LearningTrajectoryPredictionUnavailableException e) {
        consecutiveFailures += 1;
        if (consecutiveFailures >= properties.getLearnedCircuitBreakerFailureThreshold()) {
            circuitOpenUntil = Instant.now(clock).plusSeconds(properties.getLearnedCircuitBreakerOpenSeconds());
            log.warn("Trajectory sidecar circuit opened until {}", circuitOpenUntil, e);
        } else {
            log.debug("Trajectory sidecar request failed; falling back to CV", e);
        }
    }

    private record CachedPrediction(String signature, CvPredictionResult result) {}

    private record RawSample(Instant timestamp, RawPoint point) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PredictionRequest(List<TargetWindowRequest> targets) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TargetWindowRequest(String targetId, List<RawPoint> points) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RawPoint(String msgTime, double latitude, double longitude, double sog, double cog) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PredictionResponse(List<TargetPredictionResponse> predictions) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TargetPredictionResponse(String targetId, List<PredictedPointResponse> points) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PredictedPointResponse(double latitude, double longitude, int offsetSeconds) {}
}
