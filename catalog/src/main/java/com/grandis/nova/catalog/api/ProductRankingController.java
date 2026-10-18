package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.ranking.ProductRankingService;
import com.grandis.nova.catalog.ranking.ProductViewer;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

/** 상품 조회수 — 상세 화면의 조회 신호와 최근 3일 조회수 랭킹. 둘 다 로그인 없이 부른다. */
@Tag(name = "상품")
@RestController
@RequestMapping("/api/v1/products")
public class ProductRankingController {

    private final ProductRankingService rankings;
    private final Clock clock;

    public ProductRankingController(ProductRankingService rankings, Clock clock) {
        this.rankings = rankings;
        this.clock = clock;
    }

    @Operation(summary = "조회수 랭킹(로그인 없이)", description = "최근 3일(KST 날짜, 오늘 포함) 조회수 순 앞 10개 — 목록과 같은 카드. "
            + "목록에 나오는 상품만(일반 판매 · 접수 중인 사전예약). 같은 조회수면 순서는 상품 id 역순. 30초까지 늦게 반영된다. 조회수를 읽을 수 없으면 503")
    @SecurityRequirements
    @GetMapping("/ranking")
    public ResponseEntity<ApiResponse<Items<ProductListItem>>> ranking() {
        return PublicCache.ok(rankings.ranking().map(Items::new), ProductRankingService.TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "상품 조회 신호(로그인 없이)", description = "상세 화면이 뜰 때 한 번 보낸다 — 204. 같은 사람 · 같은 상품은 하루(KST) 한 번만 센다. "
            + "로그인했으면 회원으로 세고 본문은 없어도 된다. 로그인하지 않았으면 본문 anonymousId(브라우저가 만들어 두고 계속 쓰는 UUID)가 필요하다 — 없으면 400. "
            + "관리자 토큰이면 세지 않는다. 공개가 아니거나 없는 상품은 404")
    @SecurityRequirements
    @PostMapping("/{productId}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable UUID productId, @RequestBody(required = false) ViewRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof NovaAuthentication nova) {
            if (nova.getPrincipal().role() == Role.USER) {
                rankings.recordView(productId, ProductViewer.customer(nova.getPrincipal().customerId()));
            }
            return;
        }
        if (request == null || request.anonymousId() == null) {
            throw ValidationFailures.of("anonymousId", "로그인하지 않았으면 익명 id 가 필요합니다.");
        }
        rankings.recordView(productId, ProductViewer.anonymous(request.anonymousId()));
    }

    /** @param anonymousId 로그인하지 않은 브라우저의 익명 id(UUID). 로그인했으면 보지 않는다 */
    public record ViewRequest(UUID anonymousId) {
    }
}
