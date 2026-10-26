package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * 알림 대상 이름을 받는 공개 조회 둘(로그인 없이 열린 경로라 토큰을 싣지 않는다). 큐 소비 · 정리 작업에는 실을 토큰이 없다.
 * 응답에서 title 만 읽는다 — 다른 칸은 무시한다.
 */
final class TargetClients {

    private TargetClients() {
    }

    /** catalog 상품 상세. 비공개 · 미완료 상품은 404 다(이름 없음). */
    @HttpExchange("/api/v1/products")
    interface CatalogProductClient {

        @GetExchange("/{productId}")
        ApiResponse<Titled> product(@PathVariable UUID productId);
    }

    /** order 드로우 회차 상세. */
    @HttpExchange("/api/v1/draws")
    interface DrawClient {

        @GetExchange("/{drawId}")
        ApiResponse<Titled> draw(@PathVariable UUID drawId);
    }

    record Titled(String title) {
    }
}
