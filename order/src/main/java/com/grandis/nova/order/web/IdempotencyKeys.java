package com.grandis.nova.order.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 주문 생성의 Idempotency-Key 헤더 규칙 — UUID(8-4-4-4-12 hex, 대소문자 무관). 바로 구매만 요구한다.
 *
 * 헤더는 required = false 로 받고 여기서 본다 — 출처마다 필수 여부가 달라 스프링의 필수 헤더 검사를 쓸 수 없다. 없으면 다른 API 의
 * 필수 헤더 누락과 같은 IDEMPOTENCY_KEY_REQUIRED, 모양이 틀리면 VALIDATION_FAILED(field = 헤더 이름)다.
 * UUID.fromString 은 "1-1-1-1-1" 같은 짧은 모양도 받아 먼저 정규식으로 본다.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern UUID_SHAPE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private IdempotencyKeys() {
    }

    public static UUID requireUuid(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!UUID_SHAPE.matcher(key).matches()) {
            throw ValidationFailures.of(HEADER, "UUID 형식이어야 합니다.");
        }
        return UUID.fromString(key);
    }
}
