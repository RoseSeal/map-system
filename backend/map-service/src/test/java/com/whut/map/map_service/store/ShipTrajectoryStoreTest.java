package com.whut.map.map_service.store;

import com.whut.map.map_service.domain.QualityFlag;
import com.whut.map.map_service.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipTrajectoryStoreTest {

    @Test
    void appendAndGetHistory() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        ShipStatus ship = ShipStatus.builder().id("123").longitude(1.0).latitude(2.0).build();

        store.append(ship);

        List<ShipStatus> history = store.getHistory("123");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getId()).isEqualTo("123");
    }

    @Test
    void appendExceedsMaxHistory() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        for (int i = 0; i < 25; i++) {
            store.append(ShipStatus.builder().id("123").sog(i).build());
        }

        List<ShipStatus> history = store.getHistory("123");
        assertThat(history).hasSize(20);
        assertThat(history.get(0).getSog()).isEqualTo(5.0); // 0-4 should be discarded
    }

    @Test
    void removeClearsHistory() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        store.append(ShipStatus.builder().id("123").build());
        store.remove("123");

        assertThat(store.getHistory("123")).isEmpty();
    }

    @Test
    void getHistoryIsUnmodifiable() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        store.append(ShipStatus.builder().id("123").build());

        List<ShipStatus> history = store.getHistory("123");
        assertThatThrownBy(() -> history.add(ShipStatus.builder().id("123").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotIsIndependent() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        ShipStatus ship = ShipStatus.builder().id("123").longitude(10.0).build();
        store.append(ship);

        // Mutate original object
        ship.setLongitude(20.0);

        List<ShipStatus> history = store.getHistory("123");
        assertThat(history.get(0).getLongitude()).isEqualTo(10.0); // unmodified
    }

    @Test
    void snapshotCopiesQualityFlagsDefensively() {
        ShipTrajectoryStore store = new ShipTrajectoryStore();
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .qualityFlags(Set.of(QualityFlag.MISSING_HEADING, QualityFlag.MISSING_TIMESTAMP))
                .build();
        store.append(ship);

        ship.setQualityFlags(Set.of());

        List<ShipStatus> history = store.getHistory("123");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getQualityFlags())
                .containsExactlyInAnyOrder(QualityFlag.MISSING_HEADING, QualityFlag.MISSING_TIMESTAMP);
    }
}
