package com.grandis.nova.catalog.ranking;

import com.grandis.nova.catalog.cache.LocalReadCache;
import com.grandis.nova.catalog.listing.ProductListFilter;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 인기 검색어. 검색창에서 검색을 낼 때 프론트가 보내는 신호를 세고({@link SearchTermCounter}), 최근 24시간 검색 수 순으로 앞 {@link #SIZE}개를 낸다
 * (2026-10-17 · 10-18 결정). 세지 않는 검색: 결과 0건(회원 목록과 같은 노출 규칙 · q 만 건 조건으로 센다) · 금칙어({@link BannedSearchTerms}).
 */
@Service
public class PopularSearchService {

    private static final Logger log = LoggerFactory.getLogger(PopularSearchService.class);

    static final int SIZE = 10;
    /** 인기 검색어는 인스턴스 메모리에 이만큼 둔다. */
    public static final Duration TTL = Duration.ofSeconds(30);
    static final long MAX_BYTES = 64 * 1024;

    private final SearchTermCounter counter;
    private final AnonymousQuota quota;
    private final ProductListingQueryRepository listings;
    private final LocalReadCache<String, List<String>> cache;
    private final Clock clock;

    public PopularSearchService(SearchTermCounter counter, AnonymousQuota quota, ProductListingQueryRepository listings, JsonMapper json, Clock clock,
                                @Value("${nova.read-cache.enabled:true}") boolean cacheEnabled) {
        this.counter = counter;
        this.quota = quota;
        this.listings = listings;
        this.cache = new LocalReadCache<>("catalog.popular-searches", TTL, MAX_BYTES, value -> json.writeValueAsBytes(value).length, clock, cacheEnabled);
        this.clock = clock;
    }

    /**
     * 신호의 검색어 — 세는 사람과 상관없이(관리자 토큰이어도) 먼저 검사한다.
     *
     * @throws BusinessException VALIDATION_FAILED(q) — 비었거나 정규화한 검색어가 50자를 넘음
     */
    public static SearchTerm term(String q) {
        SearchTerm term = SearchTerm.of(q);
        if (term == null) {
            throw ValidationFailures.of("q", "검색어가 비었습니다.");
        }
        return term;
    }

    /**
     * 검색 한 번. 같은 사람 · 같은 검색어(대소문자 · 공백만 다른 표기 포함)는 하루(KST) 한 번만 센다. 익명이면 IP 별 익명 id 상한({@link AnonymousQuota})을
     * 넘은 새 id 는 세지 않는다. DB · Redis 가 실패하면 경고만 남기고 넘어간다(신호를 놓친다).
     */
    public void recordSearch(SearchTerm term, ProductViewer searcher) {
        if (BannedSearchTerms.contains(term)) {
            return;
        }
        Instant now = clock.instant();
        try {
            if (listings.count(new ProductListFilter(term.display(), null, null, null, null, null), now) == 0) {
                return;
            }
        } catch (DataAccessException e) {
            log.warn("검색 신호의 결과 수를 세지 못했다(DB) — 세지 않는다", e);
            return;
        }
        try {
            if (quota.admit(searcher, now)) {
                counter.record(term, searcher, now);
            }
        } catch (DataAccessException e) {
            log.warn("검색어를 세지 못했다(Redis)", e);
        }
    }

    /**
     * 최근 24시간(24~25시간, {@link SearchTermCounter}) 검색 수 순 앞 {@link #SIZE}개의 표기. 같은 수면 합침 키 역순이다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 읽을 수 없다(Redis 장애)
     */
    public ReadCache.Entry<List<String>> popular() {
        return cache.entry("searches", () -> {
            try {
                return counter.top(clock.instant(), SIZE);
            } catch (DataAccessException e) {
                log.warn("인기 검색어를 읽지 못했다(Redis)", e);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            }
        });
    }
}
