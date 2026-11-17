package io.github.zonarr.iou.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** The JSON body of every error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String message, Map<String, String> fields) {

    public ApiError(int status, String error, String message) {
        this(status, error, message, null);
    }
}
