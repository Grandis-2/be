package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.cache.CatalogLocalCaches;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.ranking.ProductRankingService;
import com.grandis.nova.catalog.web.ClientIps;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final ClientIps clientIps;
    private final Clock clock;

    public ProductRankingController(ProductRankingService rankings, ClientIps clientIps, Clock clock) {
        this.rankings = rankings;
        this.clientIps = clientIps;
        this.clock = clock;
    }

    @Operation(summary = "조회수 랭킹(로그인 없이)", description = "최근 3일(KST 날짜, 오늘 포함) 조회수 순 앞 10개 — 목록과 같은 카드. "
            + "목록에 나오는 상품만(일반 판매 · 접수 중인 사전예약). 같은 조회수면 순서는 상품 id 역순. 30초까지 늦게 반영된다. 조회수를 읽을 수 없으면 503")
    @SecurityRequirements
    @GetMapping("/ranking")
    public ResponseEntity<ApiResponse<Items<ProductListItem>>> ranking() {
        return PublicCache.ok(rankings.ranking().map(Items::new), CatalogLocalCaches.PRODUCT_RANKING_TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "상품 조회 신호(로그인 없이)", description = "상세 화면이 뜰 때 한 번 보낸다 — 204. 같은 사람 · 같은 상품은 하루(KST) 한 번만 센다. "
            + "본문 anonymousId(브라우저가 만들어 두고 계속 쓰는 UUID)는 로그인 여부와 상관없이 늘 같이 보낸다 — 유효한 회원 토큰이면 회원으로 세고 이 값은 보지 않지만, "
            + "만료 · 폐기된 토큰은 공개 경로에서 비회원으로 처리돼 이 값이 없으면 400 이다. 관리자 토큰이면 상품도 확인하지 않고 204(세지 않음). "
            + "공개가 아니거나 없는 상품은 404. 세는 중 DB · Redis 가 실패해도 204(신호를 놓친다). 비회원은 IP 하나(IPv6 는 /64)가 하루(KST)에 익명 id 20개까지만 센다 — 조회 · 검색 신호가 같은 상한을 나눠 쓴다")
    @SecurityRequirements
    @PostMapping("/{productId}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable UUID productId, @RequestBody(required = false) ViewRequest request, HttpServletRequest http) {
        Visitors.resolve(request == null ? null : request.anonymousId(), clientIps.of(http)).ifPresent(viewer -> rankings.recordView(productId, viewer));
    }

    /** @param anonymousId 로그인하지 않은 브라우저의 익명 id(UUID). 로그인했으면 보지 않는다 */
    public record ViewRequest(UUID anonymousId) {
    }
}
