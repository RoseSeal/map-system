package com.whut.map.map_service.tracking.store;

import com.whut.map.map_service.risk.config.ShipStateProperties;
import com.whut.map.map_service.shared.domain.QualityFlag;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipStateStoreTest {

    @Test
    void updateKeepsLatestShipAndRejectsOutdatedMessages() {
        ShipStateStore store = new ShipStateStore(properties(60, 60));
        OffsetDateTime now = OffsetDateTime.now();

        ShipStatus latest = ship("100000001", now, 10.0);
        ShipStatus outdated = ship("100000001", now.minusMinutes(5), 99.0);

        assertTrue(store.update(latest));
        assertFalse(store.update(outdated));

        ShipStatus current = store.get("100000001");
        assertNotNull(current);
        assertEquals(10.0, current.getLongitude());
    }

    @Test
    void purgeExpiredShipsRemovesShipsThatHaveBeenSilentTooLong() throws InterruptedException {
        ShipStateStore store = new ShipStateStore(properties(1, 1));
        store.update(ship("100000002", OffsetDateTime.now(), 20.0));

        assertNotNull(store.get("100000002"));

        Thread.sleep(1100L);
        store.purgeExpiredShips(OffsetDateTime.now());

        assertNull(store.get("100000002"));
    }

    @Test
    void outdatedMessagesDoNotRefreshLastSeenTime() throws InterruptedException {
        ShipStateStore store = new ShipStateStore(properties(1, 1));
        OffsetDateTime now = OffsetDateTime.now();

        store.update(ship("100000003", now, 30.0));
        store.update(ship("100000003", now.minusMinutes(10), 999.0));

        Thread.sleep(1100L);
        store.purgeExpiredShips(OffsetDateTime.now());

        assertNull(store.get("100000003"));
    }

    @Test
    void duplicatedTimestampMessagesDoNotRefreshLastSeenTime() throws InterruptedException {
        ShipStateStore store = new ShipStateStore(properties(1, 1));
        OffsetDateTime now = OffsetDateTime.now();

        assertTrue(store.update(ship("100000005", now, 50.0)));
        Thread.sleep(700L);
        assertFalse(store.update(ship("100000005", now, 55.0)));

        Thread.sleep(450L);
        store.purgeExpiredShips(OffsetDateTime.now());

        assertNull(store.get("100000005"));
    }

    @Test
    void returnedShipIsADefensiveCopy() {
        ShipStateStore store = new ShipStateStore(properties(60, 60));
        store.update(ship("100000004", OffsetDateTime.now(), 40.0));

        ShipStatus snapshot = store.get("100000004");
        snapshot.setLongitude(123.456);

        ShipStatus current = store.get("100000004");
        assertNotNull(current);
        assertEquals(40.0, current.getLongitude());
    }

    @Test
    void snapshotCopiesQualityFlagsDefensively() {
        ShipStateStore store = new ShipStateStore(properties(60, 60));
        Set<QualityFlag> inputFlags = new HashSet<>();
        inputFlags.add(QualityFlag.MISSING_HEADING);
        inputFlags.add(QualityFlag.MISSING_TIMESTAMP);

        ShipStatus ship = ship("100000006", OffsetDateTime.now(), 60.0);
        ship.setQualityFlags(inputFlags);
        assertTrue(store.update(ship));

        inputFlags.clear();
        ShipStatus snapshot = store.get("100000006");
        assertNotNull(snapshot);
        assertEquals(Set.of(QualityFlag.MISSING_HEADING, QualityFlag.MISSING_TIMESTAMP), snapshot.getQualityFlags());

        snapshot.getQualityFlags().clear();
        ShipStatus current = store.get("100000006");
        assertNotNull(current);
        assertEquals(Set.of(QualityFlag.MISSING_HEADING, QualityFlag.MISSING_TIMESTAMP), current.getQualityFlags());
    }

    private static ShipStateProperties properties(long expireAfterSeconds, long cleanupIntervalSeconds) {
        ShipStateProperties properties = new ShipStateProperties();
        properties.setExpireAfterSeconds(expireAfterSeconds);
        properties.setCleanupIntervalSeconds(cleanupIntervalSeconds);
        return properties;
    }

    private static ShipStatus ship(String id, OffsetDateTime msgTime, double longitude) {
        return ShipStatus.builder()
                .id(id)
                .role(ShipRole.TARGET_SHIP)
                .longitude(longitude)
                .latitude(0.0)
                .sog(0.0)
                .cog(0.0)
                .heading(null)
                .msgTime(msgTime)
                .confidence(1.0)
                .build();
    }
}


