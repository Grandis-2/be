package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.web.StrictBodies;
import org.springframework.stereotype.Component;

/**
 * 등록 본문은 **모르는 칸을 거절**한다. 앱 공통 매퍼는 모르는 칸을 버리는데(FAIL_ON_UNKNOWN_PROPERTIES=false), 이 본문에서는 오타가
 * 조용히 판매로 이어진다 — `excluded` 를 `exclude` 로 쓰면 빼려던 조합이 팔리고, `stock` 을 `stockQuantity` 로 쓰면 재고가 0 으로 들어간다(실측).
 * 그래서 이 엔드포인트만 엄격한 매퍼로 읽고, 형식 검사(애너테이션)도 여기서 돌린다. 둘 다 400 VALIDATION_FAILED 로 낸다.
 */
@Component
public class RegistrationRequestParser {

    private final StrictBodies bodies;

    public RegistrationRequestParser(StrictBodies bodies) {
        this.bodies = bodies;
    }

    /** 모르는 칸 거절 → jakarta 검증. 규칙은 {@link StrictBodies} 하나다. */
    public ProductRegistrationRequest parse(String body) {
        return bodies.parse(body, ProductRegistrationRequest.class);
    }
}
