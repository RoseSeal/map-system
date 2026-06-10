package com.whut.map.map_service.llm.agent.casegraph;

public class CaseGraphUnavailableException extends RuntimeException {

    public CaseGraphUnavailableException(String message) {
        super(message);
    }

    public CaseGraphUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
