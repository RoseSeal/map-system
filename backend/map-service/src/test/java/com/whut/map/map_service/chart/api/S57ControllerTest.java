package com.whut.map.map_service.chart.api;

import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.risk.environment.EnvironmentContextService;
import com.whut.map.map_service.risk.environment.EnvironmentRefreshResult;
import com.whut.map.map_service.risk.environment.EnvironmentStateSnapshot;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.chart.repository.S57TileRepository;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S57ControllerTest {

    @Test
    void getLayerMetadataIncludesObstructionLayer() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService(false),
                mock(RiskStreamPublisher.class)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = controller.getLayerMetadata().getBody();

        assertThat(body).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) body.get("layers");

        assertThat(layers)
                .anySatisfy(layer -> {
                    assertThat(layer).containsEntry("id", "OBSTRN");
                    assertThat(layer).containsEntry("type", "symbol");
                    assertThat(layer).containsEntry("geometryType", "point");
                    assertThat(layer)
                            .extracting(entry -> entry.get("attributes"))
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("CATOBS", "Category of obstruction")
                            .containsEntry("VALSOU", "Value of sounding or least depth (meters)");
                });
    }

    @Test
    void updateAndResetSafetyContourUseRuntimeState() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService(false),
                mock(RiskStreamPublisher.class)
        );

        Map<String, Object> updated = controller.updateSafetyContour(12.5).getBody();
        Map<String, Object> current = controller.getSafetyContour().getBody();
        Map<String, Object> reset = controller.resetSafetyContour().getBody();

        assertThat(updated).containsEntry("safetyContourDepth", 12.5);
        assertThat(current).containsEntry("safetyContourDepth", 12.5);
        assertThat(reset).containsEntry("safetyContourDepth", 10.0);
    }

    @Test
    void singleLayerTileDoesNotInjectDefaultSafetyContour() {
        S57TileRepository repository = mock(S57TileRepository.class);
        when(repository.getTile(eq("DEPARE"), eq(12), eq(1204), eq(1539), isNull()))
                .thenReturn(new byte[] {1, 2, 3});
        S57Controller controller = new S57Controller(
                repository,
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService(false),
                mock(RiskStreamPublisher.class)
        );

        ResponseEntity<byte[]> response = controller.getSingleLayerTile(12, 1204, 1539, "DEPARE", null);

        assertThat(response.getBody()).containsExactly(1, 2, 3);
        verify(repository).getTile(eq("DEPARE"), eq(12), eq(1204), eq(1539), isNull());
    }

    @Test
    void updateSafetyContourAllowsZeroDepth() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService(false),
                mock(RiskStreamPublisher.class)
        );

        ResponseEntity<Map<String, Object>> response = controller.updateSafetyContour(0.0);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("safetyContourDepth", 0.0);
    }

    @Test
    void updateSafetyContourRejectsInvalidDepth() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService(false),
                mock(RiskStreamPublisher.class)
        );

        ResponseEntity<Map<String, Object>> response = controller.updateSafetyContour(-1.0);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "depth must be a non-negative finite number");
    }

    @Test
    void updateSafetyContourPublishesEnvironmentUpdate() {
        EnvironmentContextService environmentContextService = environmentContextService(true);
        RiskStreamPublisher publisher = mock(RiskStreamPublisher.class);
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                environmentContextService,
                publisher
        );

        controller.updateSafetyContour(8.0);

        verify(publisher).publishEnvironmentUpdate(
                org.mockito.ArgumentMatchers.any(EnvironmentStateSnapshot.class),
                eq(EnvironmentUpdateReason.SAFETY_CONTOUR_UPDATED),
                eq(List.of("safety_contour_val", "hydrology", "active_alerts"))
        );
    }

    private EnvironmentContextService environmentContextService(boolean shouldPublish) {
        EnvironmentContextService service = mock(EnvironmentContextService.class);
        when(service.refresh(org.mockito.ArgumentMatchers.any(EnvironmentUpdateReason.class)))
                .thenAnswer(invocation -> new EnvironmentRefreshResult(
                        new EnvironmentStateSnapshot(1L, "2026-04-28T00:00:00Z", Map.of()),
                        invocation.getArgument(0),
                        List.of("safety_contour_val", "hydrology", "active_alerts"),
                        shouldPublish
                ));
        return service;
    }
}
