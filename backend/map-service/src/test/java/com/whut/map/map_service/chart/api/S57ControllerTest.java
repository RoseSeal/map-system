package com.whut.map.map_service.chart.api;

import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.chart.repository.S57TileRepository;
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
                new SafetyContourStateHolder(new RiskObjectMetaProperties())
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
                new SafetyContourStateHolder(new RiskObjectMetaProperties())
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
                new SafetyContourStateHolder(new RiskObjectMetaProperties())
        );

        ResponseEntity<byte[]> response = controller.getSingleLayerTile(12, 1204, 1539, "DEPARE", null);

        assertThat(response.getBody()).containsExactly(1, 2, 3);
        verify(repository).getTile(eq("DEPARE"), eq(12), eq(1204), eq(1539), isNull());
    }

    @Test
    void updateSafetyContourAllowsZeroDepth() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties())
        );

        ResponseEntity<Map<String, Object>> response = controller.updateSafetyContour(0.0);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("safetyContourDepth", 0.0);
    }

    @Test
    void updateSafetyContourRejectsInvalidDepth() {
        S57Controller controller = new S57Controller(
                mock(S57TileRepository.class),
                new SafetyContourStateHolder(new RiskObjectMetaProperties())
        );

        ResponseEntity<Map<String, Object>> response = controller.updateSafetyContour(-1.0);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "depth must be a non-negative finite number");
    }
}
