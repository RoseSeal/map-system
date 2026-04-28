package com.whut.map.map_service.chart.service;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HydrologyContextServiceTest {

    @Test
    void resolveReturnsDepthShoalAndNearestObstruction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(List.of(8.3), List.of(0.0))
                .when(jdbcTemplate).queryForList(anyString(), eq(Double.class), any(Object[].class));
        doReturn(List.of(Map.of(
                        "category", "WRECK",
                        "distance_nm", 0.71,
                        "bearing_deg", 37
                )))
                .when(jdbcTemplate).queryForList(anyString(), any(Object[].class));

        HydrologyContext context = new HydrologyContextService(provider(jdbcTemplate)).resolve(40.61, -73.83, 10.0);

        assertThat(context).isNotNull();
        assertThat(context.ownShipMinDepthM()).isEqualTo(8.3);
        assertThat(context.nearestShoalNm()).isEqualTo(0.0);
        assertThat(context.nearestObstruction()).isNotNull();
        assertThat(context.nearestObstruction().category()).isEqualTo("WRECK");
        assertThat(context.nearestObstruction().distanceNm()).isEqualTo(0.71);
        assertThat(context.nearestObstruction().bearingDeg()).isEqualTo(37);
    }

    @Test
    void resolveReturnsNullFieldsWhenEncCoverageIsMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(List.of(), List.of())
                .when(jdbcTemplate).queryForList(anyString(), eq(Double.class), any(Object[].class));
        doReturn(List.of()).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));

        HydrologyContext context = new HydrologyContextService(provider(jdbcTemplate)).resolve(40.61, -73.83, 10.0);

        assertThat(context).isNotNull();
        assertThat(context.ownShipMinDepthM()).isNull();
        assertThat(context.nearestShoalNm()).isNull();
        assertThat(context.nearestObstruction()).isNull();
    }

    @Test
    void resolveReturnsNullWhenHydrologyQueryFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doThrow(new BadSqlGrammarException("select", "select", new SQLException("missing table")))
                .when(jdbcTemplate).queryForList(anyString(), eq(Double.class), any(Object[].class));

        HydrologyContext context = new HydrologyContextService(provider(jdbcTemplate)).resolve(40.61, -73.83, 10.0);

        assertThat(context).isNull();
    }

    @Test
    void resolveReturnsNullWhenJdbcTemplateIsUnavailable() {
        HydrologyContext context = new HydrologyContextService(provider(null)).resolve(40.61, -73.83, 10.0);

        assertThat(context).isNull();
    }

    private ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbcTemplate) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        return provider;
    }
}
