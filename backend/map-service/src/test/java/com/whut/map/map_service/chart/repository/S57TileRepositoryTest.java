package com.whut.map.map_service.chart.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S57TileRepositoryTest {

    @Test
    void getTileBuildsDangerBoundaryContourForSafetyContour() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        byte[] expected = new byte[]{9, 9, 9};

        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(byte[].class),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyDouble()
        )).thenReturn(expected);

        S57TileRepository repository = new S57TileRepository(jdbcTemplate);

        byte[] actual = repository.getTile("DEPCNT", 12, 3370, 1552, 10.0);

        assertThat(actual).isEqualTo(expected);
        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ST_Boundary(ST_UnaryUnion(ST_Collect(geometry)))") &&
                                sql.contains("\"DRVAL1\" < 10.000000 OR \"DRVAL2\" < 10.000000") &&
                                !sql.contains("\"DRVAL1\" = 10.000000 OR \"DRVAL2\" = 10.000000")
                ),
                eq(byte[].class),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyDouble()
        );
    }

    @Test
    void getCompositeTileSkipsObstructionLayerWhenTableMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        byte[] expected = new byte[]{1, 2, 3};

        when(jdbcTemplate.queryForObject(
                eq("SELECT to_regclass(?) IS NOT NULL"),
                eq(Boolean.class),
                eq("enc_obstrn")
        )).thenReturn(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class))).thenReturn(expected);

        S57TileRepository repository = new S57TileRepository(jdbcTemplate);

        byte[] actual = repository.getCompositeTile(12, 3370, 1552, 10.0);

        assertThat(actual).isEqualTo(expected);
        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("soundg_mvt") &&
                                !sql.contains("obstrn_mvt") &&
                                !sql.contains("enc_obstrn")
                ),
                eq(byte[].class)
        );
    }

    @Test
    void getCompositeTileIncludesObstructionLayerWhenTableExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        byte[] expected = new byte[]{4, 5, 6};

        when(jdbcTemplate.queryForObject(
                eq("SELECT to_regclass(?) IS NOT NULL"),
                eq(Boolean.class),
                eq("enc_obstrn")
        )).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class))).thenReturn(expected);

        S57TileRepository repository = new S57TileRepository(jdbcTemplate);

        byte[] actual = repository.getCompositeTile(12, 3370, 1552, 10.0);

        assertThat(actual).isEqualTo(expected);
        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("obstrn_mvt") &&
                                sql.contains("enc_obstrn") &&
                                sql.contains("|| obstrn_mvt.tile")
                ),
                eq(byte[].class)
        );
    }

    @Test
    void getCompositeTileUsesDangerBoundaryContourWhenSafetyContourPresent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        byte[] expected = new byte[]{7, 8, 9};

        when(jdbcTemplate.queryForObject(
                eq("SELECT to_regclass(?) IS NOT NULL"),
                eq(Boolean.class),
                eq("enc_obstrn")
        )).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class))).thenReturn(expected);

        S57TileRepository repository = new S57TileRepository(jdbcTemplate);

        byte[] actual = repository.getCompositeTile(12, 3370, 1552, 10.0);

        assertThat(actual).isEqualTo(expected);
        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ST_Boundary(ST_UnaryUnion(ST_Collect(t.geometry)))") &&
                                sql.contains("10.0 as valdco") &&
                                sql.contains("t.\"DRVAL1\" < 10.000000 OR t.\"DRVAL2\" < 10.000000")
                ),
                eq(byte[].class)
        );
    }
}
