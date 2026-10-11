package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.query.OptionLookupView;
import com.grandis.nova.catalog.query.ProductOptionsQueryService;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 서비스 간 옵션 일괄 조회. order 의 장바구니(표시 · 주문 스냅샷)가 부른다. 계약: contracts/order-internal.md.
 *
 * 호출자의 JWT 를 common:security 필터가 검증해 USER · ADMIN 둘 다 허용한다(/internal/** 규칙, SecurityConfig).
 * /internal/** 은 공개 라우팅에서 빠져야 한다 — USER 토큰으로 비공개 상품까지 돌려준다.
 */
@Tag(name = "서비스 간 상품 조회")
@RestController
@RequestMapping("/internal/options")
public class InternalOptionController {

    /** 한 번에 묻는 옵션 수 상한 — 장바구니 최대 줄 수(50)와 같다. */
    static final int MAX_IDS = 50;

    private final ProductOptionsQueryService queryService;

    public InternalOptionController(ProductOptionsQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "옵션 일괄 조회(order 장바구니가 부른다)", description = "ids 1~50개(같은 id 는 한 번). 없는 옵션은 결과에서 빠진다")
    @GetMapping
    public ApiResponse<Items<OptionLookupView>> options(@RequestParam List<UUID> ids) {
        // ids=a, 처럼 빈 원소는 null 로 들어온다 — 세지도 넘기지도 않고 400 이다
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw ValidationFailures.of("ids", "빈 옵션 id 가 있습니다.");
        }
        long distinct = ids.stream().distinct().count();
        if (distinct == 0 || distinct > MAX_IDS) {
            throw ValidationFailures.of("ids", "옵션 id 는 1~%d개입니다.".formatted(MAX_IDS));
        }
        return ApiResponse.ok(new Items<>(queryService.findOptions(ids)));
    }
}
