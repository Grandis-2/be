package com.grandis.nova.order.stock.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.stock.query.StockQueryService;
import com.grandis.nova.order.web.ValidationFailures;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 서비스 간 재고 조회. catalog 의 상품 목록(품절) · 상세(옵션별 남은 수량)가 부른다. 계약: contracts/order-internal.md.
 *
 * 다른 /internal 과 달리 토큰을 받지 않는다(SecurityConfig) — 남은 수량은 상세 화면에 이미 공개되는 값이고, 비회원 목록에는 실을 토큰이
 * 없다. 그 전제로 /internal 은 공개 라우팅(ALB)에 두지 않는다.
 */
@RestController
@RequestMapping("/internal/stocks")
public class InternalStockController {

    /** 한 번에 묻는 옵션 수 상한. URL 이 약 3.7KB 로 Tomcat 헤더 기본 8KB 안이다. 넘으면 catalog 가 나눠 부른다. */
    static final int MAX_IDS = 100;

    private final StockQueryService queryService;

    public InternalStockController(StockQueryService queryService) {
        this.queryService = queryService;
    }

    /** optionIds 1~100개(쉼표로 잇거나 반복, 같은 id 는 한 번). 재고 행이 없는 옵션은 결과에서 빠진다. */
    @GetMapping
    public ApiResponse<InternalStockResponse> find(@RequestParam List<UUID> optionIds) {
        // optionIds=a, 처럼 빈 원소는 null 로 들어온다 — 세지도 넘기지도 않고 400 이다
        if (optionIds.stream().anyMatch(Objects::isNull)) {
            throw ValidationFailures.of("optionIds", "빈 옵션 id 가 있습니다.");
        }
        long distinct = optionIds.stream().distinct().count();
        if (distinct == 0 || distinct > MAX_IDS) {
            throw ValidationFailures.of("optionIds", "옵션 id 는 1~%d개입니다.".formatted(MAX_IDS));
        }
        return ApiResponse.ok(InternalStockResponse.from(queryService.findAvailable(optionIds)));
    }
}
