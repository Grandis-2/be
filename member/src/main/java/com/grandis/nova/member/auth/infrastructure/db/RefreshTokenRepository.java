package com.grandis.nova.member.auth.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 식별만. 판정하려면 아래 잠금 조회를 쓴다. */
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") byte[] hash);

    /**
     * 회전용. 행을 X 로 잠근다 — 같은 리프레시로 두 요청이 동시에 오면 하나가 커밋할 때까지 다른 하나가 기다렸다가
     * 교체된 상태를 보고 재사용으로 판정한다. 잠금 없이 읽으면 둘 다 "교체 전" 을 보고 둘 다 성공한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") byte[] hash);

    /** 체인 전체 폐기. 이미 폐기된 행은 시각을 덮지 않는다 — 처음 끊긴 시각이 남아야 조사할 수 있다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** 회원 전체 폐기(제재·탈퇴). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.customerId = :customerId and t.revokedAt is null")
    int revokeAllOf(@Param("customerId") long customerId, @Param("now") Instant now);
}
