package com.whut.map.map_service.risk.engine.trajectoryprediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.risk.config.TrajectoryPredictionProperties;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LearningTrajectoryPredictionAdapterTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private TrajectoryPredictionProperties properties;
    private RecordingCvPredictionEngine fallback;
    private LearningTrajectoryPredictionAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new TrajectoryPredictionProperties();
        properties.setLearnedUrl("http://trajectory.test/");
        properties.setLearnedWindowSize(3);
        properties.setLearnedSampleIntervalSeconds(150);
        fallback = new RecordingCvPredictionEngine();
        adapter = new LearningTrajectoryPredictionAdapter(restTemplate, properties, new ObjectMapper(), fallback);
    }

    @Test
    void sendsRawWindowAndMapsPrediction() {
        ShipStatus target = ship("target-1", "2026-04-12T09:05:00+08:00", 30.002, 114.002, 8.0, 92.0);
        List<ShipStatus> history = List.of(
                ship("target-1", "2026-04-12T09:00:00+08:00", 30.000, 114.000, 7.5, 90.0),
                ship("target-1", "2026-04-12T09:02:30+08:00", 30.001, 114.001, 7.8, 91.0),
                target
        );

        server.expect(requestTo("http://trajectory.test/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "targets": [
                            {
                              "target_id": "target-1",
                              "points": [
                                {
                                  "msg_time": "2026-04-12T09:00+08:00",
                                  "latitude": 30.0,
                                  "longitude": 114.0,
                                  "sog": 7.5,
                                  "cog": 90.0
                                },
                                {
                                  "msg_time": "2026-04-12T09:02:30+08:00",
                                  "latitude": 30.001,
                                  "longitude": 114.001,
                                  "sog": 7.8,
                                  "cog": 91.0
                                },
                                {
                                  "msg_time": "2026-04-12T09:05+08:00",
                                  "latitude": 30.002,
                                  "longitude": 114.002,
                                  "sog": 8.0,
                                  "cog": 92.0
                                }
                              ]
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "predictions": [
                            {
                              "target_id": "target-1",
                              "points": [
                                {"latitude": 30.010, "longitude": 114.010, "offset_seconds": 153},
                                {"latitude": 30.020, "longitude": 114.020, "offset_seconds": 306}
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, CvPredictionResult> results = adapter.consumeAll("own", List.of(target), id -> history);

        CvPredictionResult result = results.get("target-1");
        assertThat(result.getPredictionSource()).isEqualTo("GRU");
        assertThat(result.getTrajectory()).hasSize(32);
        assertThat(result.getTrajectory())
                .extracting(CvPredictionResult.PredictedPoint::getOffsetSeconds)
                .contains(10, 150, 153, 300, 306);
        assertThat(result.getHorizonSeconds()).isEqualTo(306);
        assertThat(fallback.consumeCount).isZero();
        server.verify();
    }

    @Test
    void warmsDedicatedBufferFromHighFrequencyMessages() {
        for (int seconds = 0; seconds < 300; seconds += 10) {
            CvPredictionResult fallbackResult = adapter.consume(
                    ship("target-1", OffsetDateTime.parse("2026-04-12T09:00:00+08:00").plusSeconds(seconds),
                            30.000 + seconds * 0.00001, 114.000 + seconds * 0.00001, 8.0, 92.0),
                    List.of()
            );
            assertThat(fallbackResult.getPredictionSource()).isEqualTo("CV");
        }

        server.expect(requestTo("http://trajectory.test/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "targets": [
                            {
                              "target_id": "target-1",
                              "points": [
                                {"msg_time": "2026-04-12T09:00+08:00"},
                                {"msg_time": "2026-04-12T09:02:30+08:00"},
                                {"msg_time": "2026-04-12T09:05+08:00"}
                              ]
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "predictions": [
                            {
                              "target_id": "target-1",
                              "points": [
                                {"latitude": 30.010, "longitude": 114.010, "offset_seconds": 153}
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        CvPredictionResult result = adapter.consume(
                ship("target-1", "2026-04-12T09:05:00+08:00", 30.003, 114.003, 8.0, 92.0),
                List.of()
        );

        assertThat(result.getPredictionSource()).isEqualTo("GRU");
        assertThat(fallback.consumeCount).isEqualTo(30);
        server.verify();
    }

    @Test
    void fallsBackToCvWhenWindowIsNotWarm() {
        ShipStatus target = ship("target-1", "2026-04-12T09:05:00+08:00", 30.002, 114.002, 8.0, 92.0);

        CvPredictionResult result = adapter.consume(target, List.of(target));

        assertThat(result.getPredictionSource()).isEqualTo("CV");
        assertThat(fallback.consumeCount).isEqualTo(1);
        server.verify();
    }

    @Test
    void fallsBackToCvWhenSidecarFails() {
        ShipStatus target = ship("target-1", "2026-04-12T09:05:00+08:00", 30.002, 114.002, 8.0, 92.0);
        List<ShipStatus> history = List.of(
                ship("target-1", "2026-04-12T09:00:00+08:00", 30.000, 114.000, 7.5, 90.0),
                ship("target-1", "2026-04-12T09:02:30+08:00", 30.001, 114.001, 7.8, 91.0),
                target
        );

        server.expect(requestTo("http://trajectory.test/predict"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"model_missing\"}"));

        Map<String, CvPredictionResult> results = adapter.consumeAll("own", List.of(target), id -> history);

        assertThat(results.get("target-1").getPredictionSource()).isEqualTo("CV");
        assertThat(fallback.consumeCount).isEqualTo(1);
        server.verify();
    }

    private ShipStatus ship(String id, String msgTime, double lat, double lon, double sog, double cog) {
        return ship(id, OffsetDateTime.parse(msgTime), lat, lon, sog, cog);
    }

    private ShipStatus ship(String id, OffsetDateTime msgTime, double lat, double lon, double sog, double cog) {
        return ShipStatus.builder()
                .id(id)
                .msgTime(msgTime)
                .latitude(lat)
                .longitude(lon)
                .sog(sog)
                .cog(cog)
                .build();
    }

    private static final class RecordingCvPredictionEngine extends CvPredictionEngine {
        private int consumeCount;

        RecordingCvPredictionEngine() {
            super(new TrajectoryPredictionProperties());
        }

        @Override
        public CvPredictionResult consume(ShipStatus message, List<ShipStatus> history) {
            consumeCount += 1;
            return CvPredictionResult.builder()
                    .targetId(message.getId())
                    .trajectory(List.of())
                    .predictionTime(message.getMsgTime().toInstant())
                    .horizonSeconds(120)
                    .predictionSource("CV")
                    .build();
        }
    }
}
