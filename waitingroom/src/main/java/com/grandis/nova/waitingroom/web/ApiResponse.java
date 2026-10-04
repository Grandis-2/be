package com.grandis.nova.waitingroom.web;

import com.grandis.nova.common.ErrorCode;

import java.time.Instant;
import java.util.Map;

/**
 * nova 응답 봉투 — 다른 서비스와 같은 모양이다.
 * traceId 는 {@link RequestIdFilter} 가 정한 요청 ID 다.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp,
        String traceId
) {

    public static <T> ApiResponse<T> success(T data, Instant timestamp, String traceId) {
        return new ApiResponse<>(true, data, null, timestamp, traceId);
    }

    public static ApiResponse<Void> failure(ErrorCode code, String message, Map<String, Object> details,
                                            Instant timestamp, String traceId) {
        return new ApiResponse<>(false, null, new ApiError(code.name(), message, details), timestamp, traceId);
    }
}
