package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 등록 본문은 **모르는 칸을 거절**한다. 앱 공통 매퍼는 모르는 칸을 버리는데(FAIL_ON_UNKNOWN_PROPERTIES=false), 이 본문에서는 오타가
 * 조용히 판매로 이어진다 — `excluded` 를 `exclude` 로 쓰면 빼려던 조합이 팔리고, `stock` 을 `stockQuantity` 로 쓰면 재고가 0 으로 들어간다(실측).
 * 그래서 이 엔드포인트만 엄격한 매퍼로 읽고, 형식 검사(애너테이션)도 여기서 돌린다. 둘 다 400 VALIDATION_FAILED 로 낸다.
 */
@Component
public class RegistrationRequestParser {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Validator validator;

    public RegistrationRequestParser(Validator validator) {
        this.validator = validator;
    }

    public ProductRegistrationRequest parse(String body) {
        ProductRegistrationRequest request;
        try {
            request = STRICT.readValue(body, ProductRegistrationRequest.class);
        } catch (UnrecognizedPropertyException e) {
            throw ValidationFailures.of(path(e), "알 수 없는 칸입니다: " + e.getPropertyName());
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

    /** Jackson 의 경로(칸 이름 · 배열 index)를 "combinations[0].exclude" 모양으로. */
    /** "optionAxes[0].values[1].<list element>" 처럼 원소 자체의 위반은 원소 경로까지만. */
    private static String field(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return path.endsWith(".<list element>") ? path.substring(0, path.length() - ".<list element>".length()) : path;
    }

    private static String path(UnrecognizedPropertyException e) {
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
        // 경로의 마지막 원소가 모르는 칸 그 자체다(Jackson 이 prependPath 로 넣는다)
        return path.isEmpty() ? e.getPropertyName() : path.toString();
    }

    static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
