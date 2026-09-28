package com.grandis.nova.common.security;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 폐기 키 두 개(세션 sid · 회원 not-before)를 MGET 한 번으로 읽는다. 요청당 Redis 왕복은 이 한 번뿐이다.
 *
 * 판정:
 * - auth:revoked-sid:{sid} 가 있으면 폐기.
 * - auth:nbf:{subject} 가 있고 그 값(epoch 초) 이 토큰 iat 이상이면 폐기. 같은 초도 폐기다 — iat·exp 가 초 정밀도라
 *   제재와 같은 초에 발급된 토큰이 살아남지 않게 경계를 안쪽으로 둔다.
 * - 값이 숫자가 아니거나 Instant 범위 밖이면 폐기로 본다. 우리가 쓴 키에 우리가 모르는 값이 있는 것은 정상이 아니다.
 *   이 갈래는 조회가 성공한 것이라 조회 실패 정책(fail-open)을 타지 않고 그 회원의 모든 경로가 401 이 되며, 재로그인해도 새 iat 가
 *   Instant.MAX 보다 앞이라 자력 복구가 없다. 운영자가 키를 지워야 풀린다. 필터가 키 이름을 로그에 남긴다.
 *
 * 조회가 실패하면 RevocationCheckFailedException. 단일 노드에서는 MGET 이 한 명령이라 부분 실패가 없다.
 * 클러스터 모드에서는 두 키(sid·subject)가 다른 슬롯이라 클라이언트가 노드별로 쪼개 보내므로 부분 실패와 왕복 2회가 가능하다.
 * "MGET 두 키 중 하나만 실패" 를 실패로 세는 이유가 그것이고, 아래 size != 2 검사가 그 경우를 받는다.
 * 클러스터에서의 실제 동작은 클러스터가 뜨는 환경에서 재야 한다. 재시도·회로 차단기·로컬 캐시는 두지 않는다 — 실패는 필터의 경로별 정책이 처리한다.
 */
@Component
public class RevocationRedisChecker implements RevocationChecker {

    private final StringRedisTemplate redis;

    public RevocationRedisChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isRevoked(TokenClaims claims) {
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(List.of(
                    AuthRedisKeys.revokedSession(claims.sessionId()),
                    AuthRedisKeys.notBefore(claims.subject())));
        } catch (RuntimeException e) {
            throw new RevocationCheckFailedException(e);
        }
        if (values == null || values.size() != 2) {
            // 실측(RevocationRedisCheckerTest): 없는 키는 null 원소로 온다. 크기가 다르면 클라이언트가 이상한 것이다.
            throw new RevocationCheckFailedException(new IllegalStateException("MGET returned " + values));
        }
        if (values.get(0) != null) {
            return true;
        }
        String notBefore = values.get(1);
        if (notBefore == null) {
            return false;
        }
        return !claims.issuedAt().isAfter(parseEpochSeconds(notBefore));
    }

    private static Instant parseEpochSeconds(String value) {
        // 두 단계가 서로 다른 예외로 실패한다: parseLong 은 NumberFormatException, ofEpochSecond 는 범위 밖에서 DateTimeException.
        // 실측(garbageNotBeforeIsRevoked): "99999999999999999" 는 long 이지만 Instant 범위(≈3.16e16) 밖이라 DateTimeException.
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.trim()));
        } catch (NumberFormatException | DateTimeException e) {
            return Instant.MAX;   // 알 수 없는 값은 "모든 토큰이 그 이전" 으로 취급 → 폐기
        }
    }
}
