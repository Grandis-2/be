package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.ranking.ProductViewer;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/** 로그인 없이 부르는 집계 신호(조회 · 검색)의 보낸 사람. 회원 토큰이면 회원, 토큰이 없으면 본문의 익명 id, 관리자 토큰이면 세지 않는다(빈 값). */
final class Visitors {

    private Visitors() {
    }

    /** @throws com.grandis.nova.common.BusinessException VALIDATION_FAILED(anonymousId) — 토큰도 익명 id 도 없다 */
    static Optional<ProductViewer> resolve(UUID anonymousId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof NovaAuthentication nova) {
            return nova.getPrincipal().role() == Role.USER ? Optional.of(ProductViewer.customer(nova.getPrincipal().customerId())) : Optional.empty();
        }
        if (anonymousId == null) {
            throw ValidationFailures.of("anonymousId", "로그인하지 않았으면 익명 id 가 필요합니다.");
        }
        return Optional.of(ProductViewer.anonymous(anonymousId));
    }
}
