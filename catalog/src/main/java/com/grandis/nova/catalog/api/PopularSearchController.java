package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.ranking.PopularSearchService;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** 인기 검색어 — 검색 신호와 최근 24시간 순위. 둘 다 로그인 없이 부른다. */
@Tag(name = "상품")
@RestController
@RequestMapping("/api/v1/products/searches")
public class PopularSearchController {

    private final PopularSearchService searches;
    private final Clock clock;

    public PopularSearchController(PopularSearchService searches, Clock clock) {
        this.searches = searches;
        this.clock = clock;
    }

    @Operation(summary = "인기 검색어(로그인 없이)", description = "최근 24시간(시간 단위로 끊어 최대 25시간) 검색 수 순 앞 10개의 검색어. "
            + "대소문자 · 공백만 다른 표기는 하나로 세고, 표기는 가장 최근에 센 것. 30초까지 늦게 반영된다. 읽을 수 없으면 503")
    @SecurityRequirements
    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<Keywords>> popular() {
        return PublicCache.ok(searches.popular().map(Keywords::new), PopularSearchService.TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "검색 신호(로그인 없이)", description = "검색창에서 검색을 낼 때 한 번 보낸다 — 204. 같은 사람 · 같은 검색어는 하루(KST) 한 번만 센다. "
            + "결과가 0건인 검색어와 금칙어는 세지 않는다(그래도 204). q 는 목록 검색과 같은 정규화 · 50자 상한(비거나 넘으면 400). "
            + "로그인했으면 회원으로 세고, 아니면 anonymousId(조회 신호와 같은 브라우저 UUID)가 필요하다 — 없으면 400. 관리자 토큰이면 세지 않는다")
    @SecurityRequirements
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void search(@RequestBody SearchRequest request) {
        Visitors.resolve(request.anonymousId()).ifPresent(searcher -> searches.recordSearch(request.q(), searcher));
    }

    /** @param anonymousId 로그인하지 않은 브라우저의 익명 id(UUID). 로그인했으면 보지 않는다 */
    public record SearchRequest(String q, UUID anonymousId) {
    }

    /** @param keywords 순위 순서의 검색어 표기 */
    public record Keywords(List<String> keywords) {
    }
}
