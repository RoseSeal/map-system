package com.whut.map.map_service.websocket.validation;

import com.whut.map.map_service.dto.websocket.BackendMessage;
import org.jspecify.annotations.Nullable;

public record ValidationResult(@Nullable BackendMessage errorMessage) {
    public static ValidationResult ok() {
        return new ValidationResult(null);
    }

    public static ValidationResult fail(BackendMessage error) {
        return new ValidationResult(error);
    }

    public boolean hasError() {
        return errorMessage != null;
    }
}
