package com.grandis.nova.member.auth.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * shop.refresh_tokens 한 행 = 발급된 리프레시 토큰 한 장. 회전할 때마다 행이 하나 는다.
 * 칸 이름·길이·NULL 여부는 DDL 그대로다. `ddl-auto=validate` 라 어긋나면 기동이 실패한다.
 *
 * 이 표에는 갱신 시각 칸이 없어 공통 BaseEntity 를 상속하지 않는다(created_at 만 있고 updated_at 이 없다).
 * `token_hash` 는 binary(32) 다 — 기본 매핑(varbinary)으로 두면 validate 가 잡으므로 칸 정의를 명시한다.
 *
 * Customer 와 달리 `@DynamicUpdate` 가 없다. 이 행을 고치는 경로가 모두 행 X 잠금 뒤에 있어
 * 두 트랜잭션이 같은 행을 동시에 쓰지 못하기 때문이다(DbRefreshTokenStore.rotate 의 findByTokenHashForUpdate).
 * 잠금 없이 이 행을 고치는 경로가 생기면 전 칼럼 UPDATE 가 남의 변경을 되돌린다 — 그때 같이 붙여야 한다.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /** 회전 체인 = 로그인 세션(sid). 액세스 토큰의 sid 클레임과 같은 값이라 세션 단위 폐기가 두 곳에서 같은 것을 가리킨다. */
    @Column(name = "family_id", nullable = false, length = 36, updatable = false)
    private String familyId;

    @Column(name = "token_hash", nullable = false, updatable = false, columnDefinition = "binary(32)")
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 새 토큰으로 교체된 시각. 이 값이 있는 토큰이 다시 오면 탈취로 본다. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** 폐기 시각. 로그아웃·재사용 탐지·전체 폐기가 찍는다. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    @Column(name = "user_agent", length = 255, updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    static RefreshToken issue(long customerId, UUID sessionId, byte[] tokenHash,
                              Instant issuedAt, Instant expiresAt, String clientIp, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.customerId = customerId;
        token.familyId = sessionId.toString();
        token.tokenHash = tokenHash;
        token.createdAt = issuedAt;
        token.expiresAt = expiresAt;
        token.clientIp = clientIp;
        token.userAgent = userAgent;
        return token;
    }

    /** 회전. 교체 시각은 한 번만 찍는다 — 두 번째 호출은 재사용이라 여기까지 오지 않는다. */
    void markRotated(Instant when) {
        this.rotatedAt = when;
    }

    Long getId() {
        return id;
    }

    long getCustomerId() {
        return customerId;
    }

    UUID getSessionId() {
        return UUID.fromString(familyId);
    }

    String getFamilyId() {
        return familyId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getRotatedAt() {
        return rotatedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
