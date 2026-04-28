package com.whut.map.map_service.chart.service;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class SafetyContourStateHolder {

    private final double defaultDepthMeters;
    private final AtomicReference<Double> currentDepthMeters;

    public SafetyContourStateHolder(RiskObjectMetaProperties properties) {
        this.defaultDepthMeters = properties.getSafetyContourVal();
        this.currentDepthMeters = new AtomicReference<>(defaultDepthMeters);
    }

    public double getDefaultDepthMeters() {
        return defaultDepthMeters;
    }

    public double getCurrentDepthMeters() {
        return currentDepthMeters.get();
    }

    public double updateDepthMeters(double depthMeters) {
        if (!Double.isFinite(depthMeters) || depthMeters < 0) {
            throw new IllegalArgumentException("Safety contour depth must be a non-negative finite number");
        }
        currentDepthMeters.set(depthMeters);
        return depthMeters;
    }

    public double resetToDefault() {
        currentDepthMeters.set(defaultDepthMeters);
        return defaultDepthMeters;
    }
}
