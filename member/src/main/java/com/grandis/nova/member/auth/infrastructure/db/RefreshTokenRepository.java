package com.grandis.nova.member.auth.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

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

    /**
     * 체인의 만료 시각(회전은 만료를 물려받으므로 한 체인의 행은 모두 같다). 행이 없으면 null. 잠그지 않는 읽기 — 폐기 전에 "탐지 창이 지났는가" 를
     * 기다림 없이 보려고 쓴다. UPDATE 에 만료 조건을 붙이는 것으로는 안 된다: MySQL 은 잠긴 행을 기다린 뒤에야 조건을 본다(실측 lock wait timeout).
     */
    @Query("select max(t.expiresAt) from RefreshToken t where t.familyId = :familyId")
    Instant expiryOfFamily(@Param("familyId") String familyId);

    /** cutoff 이하에 만료된 행의 id 를 만료 순으로 최대 limit 개. 잠그지 않는 읽기라 진행 중인 회전을 기다리지 않는다. cutoff 는 RefreshTokenCleanup 이 정한다. */
    @Query("select t.id from RefreshToken t where t.expiresAt <= :cutoff order by t.expiresAt limit :limit")
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * 고른 id 만 지운다. 만료 조건을 다시 거는 것은 방어일 뿐이다 — 만료 시각은 바뀌지 않는 칸이라 고른 id 는 이미 조건을 만족한다. 여러 인스턴스가
     * 같은 id 를 지워도 안전한 이유는 두 번째 기본키 삭제가 0행이기 때문이다. 표 주석대로 이 표만 물리 삭제를 허용한다 — 이력이 아니라 유효 토큰 목록이다.
     * `DELETE … WHERE expires_at <= ? ORDER BY … LIMIT` 한 문장으로 지우면 MySQL 이 표를 훑으며 **유효 행의 잠금까지 기다린다**(실측: 회전이 잡은
     * 유효 행 때문에 50초 뒤 lock wait timeout. 인덱스 힌트를 줘도 같았다). 기본키로 지우면 목록의 만료 행만 잠근다.
     */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken t where t.id in :ids and t.expiresAt <= :cutoff")
    int deleteExpired(@Param("ids") List<UUID> ids, @Param("cutoff") Instant cutoff);

    /**
     * 회원 전체 폐기(제재 · 탈퇴)의 대상 — 그 회원의 폐기 안 된 행 중 탐지 창(만료 + 액세스 유효기간) 안의 id. 잠그지 않는 읽기다. 창이 지난 행은 막을
     * 액세스 토큰이 없고 정리가 지우는 중일 수 있어 뺀다. UPDATE 에 조건만 붙이면 MySQL 이 잠긴 행을 기다린 뒤 조건을 봐서 정리와 맞물린다(실측).
     */
    @Query("select t.id from RefreshToken t where t.customerId = :customerId and t.revokedAt is null and t.expiresAt > :windowStart")
    List<UUID> findLiveIdsOf(@Param("customerId") UUID customerId, @Param("windowStart") Instant windowStart);

    /** 고른 행만 폐기한다(기본키). 이미 폐기된 행은 시각을 덮지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id in :ids and t.revokedAt is null")
    int revokeByIds(@Param("ids") List<UUID> ids, @Param("now") Instant now);
}
