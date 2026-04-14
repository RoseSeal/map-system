package com.whut.map.map_service.risk.engine.encounter;

import com.whut.map.map_service.risk.config.EncounterProperties;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncounterClassifierTest {

    private EncounterClassifier classifier;
    private EncounterProperties props;

    @BeforeEach
    void setUp() {
        props = new EncounterProperties();
        classifier = new EncounterClassifier(props);
    }

    @Test
    void testHeadOn() {
        // 正对遇: ownCOG=0, target at 0 (bearingOwnToTarget=0), targetCOG=180
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(0.0).build();
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(30.1).longitude(114.0).cog(180.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.HEAD_ON, result.getEncounterType());
    }

    @Test
    void testHeadOnBoundary() {
        // 准对遇（边界内）: ownCOG=0, target at 15 (bearingOwnToTarget=15), targetCOG=180
        // courseDiff = 180 >= 170
        // relBearing = 15 <= 15
        // target relBearing to own: trueBearing(tgt->own) = 195. relBearingFromTarget = (195-180) = 15 <= 15
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(0.0).build();
        // Calculate lat/lon for bearing 15
        double dist = 0.01;
        double lat2 = 30.0 + dist * Math.cos(Math.toRadians(15.0));
        double lon2 = 114.0 + dist * Math.sin(Math.toRadians(15.0));
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(lat2).longitude(lon2).cog(180.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.HEAD_ON, result.getEncounterType());
    }

    @Test
    void testCrossingInsteadOfHeadOnDueToBearing() {
        // 准对遇（边界外，方位超 15°）
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(0.0).build();
        double dist = 0.01;
        double lat2 = 30.0 + dist * Math.cos(Math.toRadians(20.0));
        double lon2 = 114.0 + dist * Math.sin(Math.toRadians(20.0));
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(lat2).longitude(lon2).cog(180.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.CROSSING, result.getEncounterType());
    }

    @Test
    void testOvertaking() {
        // 正追越: ownCOG=0, target at 180 (bearingOwnToTarget=180), targetCOG=0
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(0.0).build();
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(29.9).longitude(114.0).cog(0.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.OVERTAKING, result.getEncounterType());
    }

    @Test
    void testCrossingInsteadOfOvertakingDueToCourseDiff() {
        // 追越误判修正：在船尾弧但反向
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(0.0).build();
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(29.9).longitude(114.0).cog(180.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.CROSSING, result.getEncounterType());
    }

    @Test
    void testUndefinedDueToInvalidCog() {
        ShipStatus own = ShipStatus.builder().id("own").latitude(30.0).longitude(114.0).cog(360.0).build();
        ShipStatus tgt = ShipStatus.builder().id("tgt").latitude(30.1).longitude(114.0).cog(180.0).build();

        EncounterClassificationResult result = classifier.classify(own, tgt);
        assertEquals(EncounterType.UNDEFINED, result.getEncounterType());
    }
}
