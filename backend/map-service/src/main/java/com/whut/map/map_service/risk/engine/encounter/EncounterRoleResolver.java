package com.whut.map.map_service.risk.engine.encounter;

import org.springframework.stereotype.Component;

@Component
public class EncounterRoleResolver {

    public OwnShipRole resolve(EncounterClassificationResult classification) {
        if (classification == null) {
            return OwnShipRole.UNKNOWN;
        }
        return switch (classification.getEncounterType()) {
            case HEAD_ON -> OwnShipRole.MUTUAL_ACTION;
            case OVERTAKING -> OwnShipRole.STAND_ON;
            case CROSSING -> resolveCrossing(classification.getRelativeBearingDeg());
            case UNDEFINED -> OwnShipRole.UNKNOWN;
        };
    }

    private OwnShipRole resolveCrossing(double relativeBearingDeg) {
        // Target approaching from starboard side (right half) → own ship is give-way (Rule 15)
        // Target approaching from port side (left half) → own ship is stand-on (Rule 17)
        // Relative bearing is measured from own ship's bow (COG reference), clockwise [0, 360)
        // Starboard half: (0, 180), Port half: (180, 360)
        // Edge cases (0 or 180) are already handled by HEAD_ON and OVERTAKING classifiers
        if (relativeBearingDeg > 0 && relativeBearingDeg < 180) {
            return OwnShipRole.GIVE_WAY;
        }
        return OwnShipRole.STAND_ON;
    }
}
