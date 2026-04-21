package com.whut.map.map_service.chart.api;

import com.whut.map.map_service.chart.repository.S57TileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class S57ControllerTest {

    @Test
    void getLayerMetadataIncludesObstructionLayer() {
        S57Controller controller = new S57Controller(mock(S57TileRepository.class));

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
}
