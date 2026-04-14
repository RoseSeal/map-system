package com.whut.map.map_service.risk.engine.safety;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShipDomainResult {

    public static final String SHAPE_ELLIPSE = "ellipse";

    private double foreNm;
    private double aftNm;
    private double portNm;
    private double stbdNm;
    private String shapeType;
}
