package com.grandis.nova.catalog.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 요청 주체가 관리자인가. 비공개 상품의 미리보기 같은 "관리자만 더 보는" 판정에 쓴다.
 *
 * 미리보기는 같은 공개 URL 이 호출자에 따라 다른 응답을 주는 자리다. 응답이 공유 캐시에 남으면 비공개 상품이 샌다.
 * 지금은 Spring Security 기본값이 모든 응답에 Cache-Control: no-store 를 붙인다(실측). 목록 성능 때문에 캐시 헤더를 켜게 되면
 * 미리보기 응답은 빼야 한다 — ProductDetailApiTest 가 관리자 미리보기 응답의 no-store 를 단언한다.
 * 토큰 검증 필터 채택 전에는 늘 익명이라 false(닫힌 쪽)다.
 */
public final class Viewers {

    private static final String ADMIN = "ROLE_ADMIN";

    private Viewers() {
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN.equals(authority.getAuthority()));
    }
}
