package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.banner.BannerQueryService;
import com.grandis.nova.catalog.banner.BannerViews;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** 메인 배너(로그인 없이). */
@Tag(name = "배너")
@RestController
@RequestMapping("/api/v1/banners")
public class BannerController {

    private final BannerQueryService banners;
    private final Clock clock;

    public BannerController(BannerQueryService banners, Clock clock) {
        this.banners = banners;
        this.clock = clock;
    }

    @Operation(summary = "메인 배너(로그인 없이)", description = "관리자가 켠 배너 중 연결 대상이 지금 살아 있는 것만, 관리자가 정한 순서로. 대상이 일반 판매 상품이면 목록에 나오는 동안, 홍보면 홍보 기간 동안만 — 끝나면 저절로 빠진다(10초까지 늦을 수 있다). target.type 으로 상품 상세 · 홍보 페이지 주소를 만든다")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<Items<BannerViews.Shown>>> list() {
        return PublicCache.ok(banners.shown().map(Items::new), BannerQueryService.TTL, PublicCache.PRODUCT, clock);
    }
}
