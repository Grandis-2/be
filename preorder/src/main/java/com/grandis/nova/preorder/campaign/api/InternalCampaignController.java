package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.application.CampaignScheduleQuery;
import com.grandis.nova.preorder.web.Items;
import com.grandis.nova.preorder.web.ValidationFailures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * catalog 가 회차 일정을 묻는 서비스 간 API. 계약: contracts/preorder-internal.md.
 * 다른 /internal 과 달리 토큰을 받지 않는다(SecurityConfig) — 오픈 · 마감 시각은 공개 화면의 값이고 비회원 목록에는 실을 토큰이 없다.
 */
@Tag(name = "서비스 간 · 회차 일정")
@RestController
@RequestMapping("/internal/preorders/campaigns")
class InternalCampaignController {

    /** 한 번에 묻는 상품 수 상한. URL 이 약 3.7KB 로 Tomcat 헤더 기본 8KB 안이다. 넘으면 catalog 가 나눠 부른다. */
    static final int MAX_IDS = 100;

    private final CampaignScheduleQuery query;
    private final Clock clock;

    InternalCampaignController(CampaignScheduleQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    @Operation(summary = "접수 중 · 곧 열릴 회차 — catalog 목록의 접수 중 조건용. 접수 중인지는 받는 쪽이 시각으로 판정")
    @GetMapping("/open")
    public ApiResponse<OpenCampaignsResponse> open() {
        Instant now = clock.instant();
        return ApiResponse.ok(new OpenCampaignsResponse(toResponses(query.findOpenOrOpeningSoon(now)), now));
    }

    /** productIds 1~100개(쉼표로 잇거나 반복, 같은 id 는 한 번). 회차가 없는 상품은 결과에서 빠진다. */
    @Operation(summary = "상품별 회차 — 회차가 없는 상품은 빠진다")
    @GetMapping
    public ApiResponse<Items<InternalCampaignResponse>> find(@RequestParam List<UUID> productIds) {
        // productIds=a, 처럼 빈 원소는 null 로 들어온다 — 세지도 넘기지도 않고 400 이다
        if (productIds.stream().anyMatch(Objects::isNull)) {
            throw ValidationFailures.of("productIds", "빈 상품 id 가 있습니다.");
        }
        List<UUID> distinct = productIds.stream().distinct().toList();
        if (distinct.isEmpty() || distinct.size() > MAX_IDS) {
            throw ValidationFailures.of("productIds", "상품 id 는 1~%d개입니다.".formatted(MAX_IDS));
        }
        return ApiResponse.ok(new Items<>(toResponses(query.findSchedules(distinct))));
    }

    private static List<InternalCampaignResponse> toResponses(List<CampaignSchedule> schedules) {
        return schedules.stream().map(InternalCampaignResponse::from).toList();
    }
}
