package com.whut.map.map_service.risk.engine.trajectoryprediction;

public class LearningTrajectoryPredictionUnavailableException extends RuntimeException {

    public LearningTrajectoryPredictionUnavailableException(String message) {
        super(message);
    }

    public LearningTrajectoryPredictionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
