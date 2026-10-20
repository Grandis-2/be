package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminRefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokens;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * 관리자 세션의 리프레시 jti 를 두는 Lua 비교교환 저장소. 회원 리프레시는 여기가 아니라 `shop.refresh_tokens` 가 정본이다.
 * 회전은 GET → 비교 → SET 을 서버 안에서 한 번에 한다. 클라이언트에서 GET 하고 SET 하면 그 사이에 다른 요청이 끼어 둘 다 성공한다.
 *
 * <p>회전 유예는 세션 키와 따로 둔다 — 세션 키(`auth:refresh:{sid}`)는 늘 지금 jti 하나라 이 형식을 모르는 옛 버전도 그대로 읽는다(배포 중 섞여도 ·
 * 롤백해도 관리자 세션이 끊기지 않는다). 유예 키(`auth:refresh-grace:{sid}`)는 회전 때 `바로앞jti|다음jti|유예끝(Redis 서버 시각, epoch ms)` 을 유예
 * 시간만큼 둔다. 바로 앞 jti 가 다시 오면 <b>지금 jti 가 그 회전이 만든 다음 jti 와 같을 때만</b>(다음 토큰이 아직 안 쓰였을 때만) 다시 회전하고, 유예
 * 키의 다음 jti 를 새 jti 로 바꾼다(유예 끝은 그대로라 창이 늘지 않는다). 지금 jti 가 다르면 다음 토큰이 이미 쓰인 것이다 — 이 형식을 모르는 옛 버전이
 * 회전해 유예 키를 고치지 않은 경우(배포 중 섞임)도 여기서 재사용으로 걸린다 — 세션이 끊겨 옛 버전이 준 토큰의 보유자도 401 이고, 버전이 하나일 때의
 * 재사용 탐지와 같은 결과다. 시각은 앱이 아니라 Redis 의 TIME 으로 잰다 — 인스턴스마다 시계가
 * 달라도 한 곳에서 판정한다. 유예 값에 구분자가 모자라거나 유예 끝이 숫자로 읽히지 않으면 유예 없이 지금 jti 만 비교한다(스크립트 오류 500 이 나지
 * 않게 — Lua tonumber 가 읽는 값(inf 등)은 유예가 된다. 유예 키는 서버만 쓰고 30초 뒤 사라진다). 두 키를 한 스크립트에서 쓰므로 같은 슬롯이어야 한다 —
 * 운영 Valkey 는 클러스터 모드가 아니다(nova-infra 복제 그룹, 샤드 하나). 클러스터로 바꾸면 두 키에 해시 태그가 필요하다.
 */
@Repository
public class RefreshTokenRedisStore implements AdminRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRedisStore.class);

    /**
     * KEYS[1]=세션 키, KEYS[2]=유예 키, ARGV[1]=기대 jti, ARGV[2]=새 jti, ARGV[3]=TTL(ms), ARGV[4]=유예(ms).
     * 1 이면 교체됨, 2 면 유예 안의 다시 회전, 0 이면 키 없음 또는 그 밖의 재사용.
     */
    static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            if current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                redis.call('SET', KEYS[2], ARGV[1] .. '|' .. ARGV[2] .. '|' .. string.format('%.0f', now + tonumber(ARGV[4])), 'PX', ARGV[4])
                return 1
            end
            local grace = redis.call('GET', KEYS[2])
            if grace then
                local first = string.find(grace, '|', 1, true)
                local second = first and string.find(grace, '|', first + 1, true)
                if second then
                    local previous = string.sub(grace, 1, first - 1)
                    local successor = string.sub(grace, first + 1, second - 1)
                    local graceUntil = tonumber(string.sub(grace, second + 1)) or 0
                    if previous == ARGV[1] and successor == current and now < graceUntil then
                        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                        -- 유예 판정은 값의 유예 끝이 정한다. KEEPTTL 은 키가 늦게까지 남지 않게 하는 정리용이다
                        redis.call('SET', KEYS[2], previous .. '|' .. ARGV[2] .. '|' .. string.format('%.0f', graceUntil), 'KEEPTTL')
                        return 2
                    end
                end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RefreshTokenRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(UUID sessionId, UUID refreshTokenId, Duration ttl) {
        redis.opsForValue().set(AuthRedisKeys.refresh(sessionId), refreshTokenId.toString(), ttl);
    }

    @Override
    public boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl) {
        // toMillis 는 내림이라 1ms 미만이면 PX 0 이 되고 Redis 가 "invalid expire time" 으로 거절해 500 이 된다. 하한 1ms.
        long ttlMillis = Math.max(1, ttl.toMillis());
        Long result = redis.execute(ROTATE, List.of(AuthRedisKeys.refresh(sessionId), AuthRedisKeys.refreshGrace(sessionId)),
                expectedRefreshTokenId.toString(), newRefreshTokenId.toString(), String.valueOf(ttlMillis),
                String.valueOf(RefreshTokens.ROTATION_GRACE.toMillis()));
        if (Long.valueOf(2L).equals(result)) {
            log.info("admin refresh rotated again within grace sid={}", sessionId.toString().substring(0, 8));
        }
        return result != null && result > 0;
    }

    @Override
    public void delete(UUID sessionId) {
        redis.delete(List.of(AuthRedisKeys.refresh(sessionId), AuthRedisKeys.refreshGrace(sessionId)));
    }
}
