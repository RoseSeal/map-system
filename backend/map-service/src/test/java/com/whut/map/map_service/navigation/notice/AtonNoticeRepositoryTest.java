package com.whut.map.map_service.navigation.notice;

import com.whut.map.map_service.navigation.NavigationTestSupport;
import com.whut.map.map_service.navigation.route.RouteGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AtonNoticeRepositoryTest {

    @Test
    void loadsCommittedStateWithCutoffAndValidPositions() {
        AtonNoticeRepository repository = NavigationTestSupport.atonRepository(NavigationTestSupport.productionProperties());

        assertThat(repository.dataCutoff()).isEqualTo("2026-10-23");
        assertThat(repository.atons()).isNotEmpty().allSatisfy(aton -> assertThat(aton.position()).isNotNull());
        assertThat(repository.findNotice("t20260924_490370")).isPresent();
    }

    @Test
    void demoRoutePassesBothWuhanSecondBridgeBuoysInsideBuffer() {
        var properties = NavigationTestSupport.productionProperties();
        var route = NavigationTestSupport.routeRepository(properties).current();
        var atons = NavigationTestSupport.atonRepository(properties).validAtons();

        RouteAtonIndex index = RouteAtonIndex.build(RouteGeometry.of(route), atons, 200.0);

        assertThat(index.entries())
                .filteredOn(entry -> entry.aton().name().startsWith("武汉二桥#"))
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.lateralMeters()).isCloseTo(120.0, within(5.0)));
    }

    @Test
    void skipsUnknownStateAndKeepsNonValidForDisplayOnly() {
        AtonNoticeRepository repository = NavigationTestSupport.atonRepository(NavigationTestSupport.testProperties());

        assertThat(repository.atons()).extracting(AtonRecord::atonId)
                .containsExactly("valid-near", "removed-near", "valid-far");
        assertThat(repository.validAtons()).extracting(AtonRecord::atonId)
                .containsExactly("valid-near", "valid-far");
    }

    @Test
    void rejectsAidWithoutPosition() {
        AtonRecord missing = new AtonRecord("x", "w", "n", false, AtonState.VALID, null, "SET", null, List.of());

        assertThatThrownBy(() -> AtonNoticeRepository.accept(List.of(missing)))
                .isInstanceOf(IllegalStateException.class);
    }
}
