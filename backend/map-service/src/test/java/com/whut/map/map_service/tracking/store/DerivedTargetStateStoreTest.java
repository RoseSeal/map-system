package com.whut.map.map_service.tracking.store;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DerivedTargetStateStoreTest {

    @Test
    void getAllReturnsUnmodifiableView() {
        DerivedTargetStateStore store = new DerivedTargetStateStore();
        store.put("target-1", new TargetDerivedSnapshot("target-1", null, null, null, null, null));

        Map<String, TargetDerivedSnapshot> snapshot = store.getAll();

        assertThat(snapshot).containsKey("target-1");
        assertThatThrownBy(() -> snapshot.remove("target-1"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.get("target-1")).isNotNull();
    }
}
