package com.grandis.nova.common.security;

/**
 * 인증 주체 종류. api-spec 의 권한 표기와 같은 값이다.
 * USER = 카카오 회원(sub 는 customers.id), ADMIN = 회원 테이블 밖 환경변수 단일 계정(sub 는 고정 문자열 "admin").
 */
public enum Role {
    USER,
    ADMIN
}
