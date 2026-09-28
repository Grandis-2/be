package com.grandis.nova.member.auth.application;

import java.time.Duration;
import java.util.UUID;

/**
 * 관리자 세션의 리프레시 저장소(Redis). 회원과 달리 `refresh_tokens` 표를 쓸 수 없다 —
 * 그 표의 `customer_id` 가 `customers` 를 가리키는 NOT NULL 외래키인데 관리자 계정은 회원 표 밖에 있기 때문이다.
 *
 * 관리자 리프레시는 JWT 이고 이 저장소는 세션마다 "현재 유효한 jti" 하나를 든다. 회전은 비교교환이라
 * 두 요청이 동시에 회전해도 하나만 성공한다. 관리자 계정은 하나뿐이고 세션도 드물어 목록·이력을 두지 않는다.
 */
public interface AdminRefreshTokenStore {

    void save(UUID sessionId, UUID refreshTokenId, Duration ttl);

    /** 저장된 jti 가 expected 와 같을 때만 교체한다. false 는 "이미 회전됨(재사용)" 또는 "없음(로그아웃·만료)". */
    boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl);

    void delete(UUID sessionId);
}
