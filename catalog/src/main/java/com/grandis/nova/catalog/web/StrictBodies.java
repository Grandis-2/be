package com.grandis.nova.catalog.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 쓰기 본문은 **모르는 칸을 거절**한다. 앱 공통 매퍼는 모르는 칸을 버리는데(FAIL_ON_UNKNOWN_PROPERTIES=false), 관리자 본문에서는
 * 오타(`exclude` · `stockQuantity`)가 조용히 잘못된 저장으로 이어지므로 전용 매퍼로 읽고 400 에 칸 경로를 싣는다. 그다음 jakarta 검증.
 * 등록 · 수정 · 옵션 추가가 같이 쓴다.
 */
@Component
public class StrictBodies {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 같은 키가 두 번 오면 뒤의 것이 조용히 이기는 대신 400(body) — {"color":"블랙","color":"레드"}
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Validator validator;

    public StrictBodies(Validator validator) {
        this.validator = validator;
    }

    public <T> T parse(String body, Class<T> type) {
        T request;
        try {
            request = STRICT.readValue(body == null ? "" : body, type);
        } catch (UnrecognizedPropertyException e) {
            String path = path(e);
            throw ValidationFailures.of(path.isEmpty() ? e.getPropertyName() : path, "알 수 없는 칸입니다: " + e.getPropertyName());
        } catch (MismatchedInputException e) {
            // 모르는 enum 값 · 숫자 자리의 문자열처럼 칸은 알지만 모양이 틀린 것 — 칸 경로가 있으면 그 칸의 400
            String path = path(e);
            throw ValidationFailures.of(path.isEmpty() ? "body" : path, "형식이 맞지 않습니다.");
        } catch (RuntimeException e) {
            throw ValidationFailures.of("body", "본문을 읽을 수 없습니다.");
        }
        if (request == null) {
            throw ValidationFailures.of("body", "본문이 비었습니다.");
        }
        List<ApiError.Violation> violations = validator.validate(request).stream()
                .sorted(Comparator.comparing(v -> field(v)))
                .map(v -> new ApiError.Violation(field(v), v.getMessage()))
                .toList();
        if (!violations.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, Map.of("violations", violations));
        }
        return request;
    }

    /** "optionAxes[0].values[1].<list element>" 처럼 원소 자체의 위반은 원소 경로까지만. */
    private static String field(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return path.endsWith(".<list element>") ? path.substring(0, path.length() - ".<list element>".length()) : path;
    }

    /** Jackson 의 경로(칸 이름 · 배열 index)를 "combinations[0].exclude" 모양으로. 경로의 마지막 원소가 문제의 칸 그 자체다. 없으면 빈 문자열. */
    private static String path(MismatchedInputException e) {
        StringBuilder path = new StringBuilder();
        for (var reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
