package com.whut.map.map_service.websocket.validation;

import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import org.jspecify.annotations.Nullable;

public record ValidationResult(
        @Nullable ChatErrorCode errorCode,
        @Nullable String errorMessage
) {
    public static ValidationResult ok() {
        return new ValidationResult(null, null);
    }

    public static ValidationResult fail(ChatErrorCode errorCode, String errorMessage) {
        return new ValidationResult(errorCode, errorMessage);
    }

    public boolean hasError() {
        return errorCode != null;
    }
}
