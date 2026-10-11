package com.grandis.nova.common.security;

/**
 * "이 토큰이 서버 쪽에서 끊겼는가" 를 묻는 포트. 필터가 요청마다 한 번 부른다.
 *
 * 세 가지 답이 있고 셋을 섞지 않는다.
 * - false: 표식이 없다. 통과.
 * - true: sid 폐기 표식이 있거나, 회원 not-before 가 토큰 발급 시각 이후다. 거부.
 * - RevocationCheckFailedException: 조회 자체가 안 됐다(연결·타임아웃·부분 실패). 통과냐 거부냐는 경로별 정책이 정하며
 *   그 판단은 필터가 한다. 이 포트는 "못 봤다" 를 false 로 바꾸지 않는다 — 어댑터가 실패를 false 로 삼키면
 *   소비자가 "못 봤다" 와 "폐기 아님" 을 구분할 수 없게 된다.
 */
public interface RevocationChecker {

    boolean isRevoked(TokenClaims claims);

    /**
     * 폐기된 토큰이 같은 회원의 새 로그인에 밀려 끊긴 세션의 것인가. {@link #isRevoked} 가 true 일 때만 묻는다 — 401 에 이유를 싣는 데만 쓰고
     * 판정에는 쓰지 않는다. 기본 구현은 false(이유 없는 401). 조회가 안 되면 RevocationCheckFailedException 이고 부르는 쪽이 이유 없이 401 을 낸다.
     */
    default boolean isReplaced(TokenClaims claims) {
        return false;
    }
}
