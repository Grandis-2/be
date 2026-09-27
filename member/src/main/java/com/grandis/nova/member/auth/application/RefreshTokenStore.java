package com.grandis.nova.member.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 회원 리프레시 토큰의 **정본** 저장소. 구현은 `shop.refresh_tokens` 다.
 *
 * 한 세션(sid)이 회전 체인 하나(`family_id`)이고, 체인의 각 토큰이 행 하나다. 회전할 때마다 행이 하나 는다 —
 * 그래야 "이미 교체된 토큰이 다시 왔다"(탈취)를 알 수 있다. 원문은 저장하지 않고 SHA-256 만 든다.
 *
 * 관리자 세션은 이 저장소를 쓰지 않는다 — `refresh_tokens.customer_id` 가 `customers` 를 가리키는 NOT NULL 외래키라
 * 회원 표 밖에 있는 관리자 계정의 행을 만들 수 없다. 관리자는 Redis 저장소를 그대로 쓴다(TokenService 가 역할로 가른다).
 */
public interface RefreshTokenStore {

    /** 로그인. 발급한 원문의 지문으로 체인의 첫 행을 만든다. */
    void save(UUID sessionId, String subject, String rawToken, Instant expiresAt, ClientInfo client);

    /**
     * 제시된 원문이 가리키는 세션. 폐기·회전 여부와 무관하게 **식별만** 한다 — 회전 전 폐기 표식 조회와 로그아웃이 쓴다.
     * 판정은 여기서 하지 않는다. 판정은 {@link #rotate} 가 행을 잠그고 한다.
     */
    Optional<Session> find(String rawToken);

    /**
     * 회전(RTR). 행을 잠그고 검사한 뒤 정상이면 그 행에 교체 시각을 찍고 같은 체인으로 새 행을 만든다.
     *
     * 재사용(이미 교체된 원문)이면 같은 체인의 살아 있는 행을 전부 폐기하고 {@link Rotation.Status#REUSED} 를 돌려준다.
     * **예외가 아니라 값으로 돌려주는 이유**: 예외로 빠져나가면 그 폐기가 롤백된다. 폐기는 커밋되어야 한다.
     */
    Rotation rotate(String presentedRawToken, String newRawToken, ClientInfo client);

    /** 세션 하나(체인 전체)를 폐기한다. 로그아웃·재사용 탐지. */
    void revokeSession(UUID sessionId);

    /** 회원의 살아 있는 리프레시를 전부 폐기한다(제재·탈퇴). subject 가 회원 번호가 아니면 아무것도 하지 않는다. */
    void revokeAllOf(String subject);

    /**
     * @param issuedAt 이 토큰을 발급한 시각. 회원 단위 not-before 와 비교하는 기준이라 **초로 자른 값**이다
     *                 (표식이 epoch 초라 밀리초를 남기면 같은 초의 발급이 경계 앞뒤로 갈린다)
     */
    record Session(UUID sessionId, String subject, Instant issuedAt, Instant expiresAt) {
    }

    /** 회전 결과. ROTATED 가 아니면 새 토큰은 만들어지지 않았다. */
    record Rotation(Status status, UUID sessionId, String subject, Instant expiresAt) {

        public enum Status { ROTATED, NOT_FOUND, EXPIRED, REVOKED, REUSED }

        public static Rotation rejected(Status status, UUID sessionId, String subject) {
            return new Rotation(status, sessionId, subject, null);
        }
    }
}
