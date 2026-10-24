package com.grandis.nova.order.client.member;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 주문 목록 · 상세의 회원 이름(가린 값). 표시용이라 못 받아도 응답은 나간다 — 받지 못한 회원의 이름은 비어 있다.
 *
 * <ul>
 *     <li>한 화면에 member 호출은 많아야 한 번이다: 같은 id 는 한 번, 캐시에 없는 id 만 묶어 부른다(한 쪽 최대 100줄 = 호출 상한 100개).</li>
 *     <li>이름을 {@link #TTL} 동안 캐시한다 — 가린 이름(첫 글자 + **)은 거의 바뀌지 않는다. 없는 회원도 "없음" 으로 캐시해 매번 묻지 않는다.</li>
 *     <li>요청 스레드에서 부르므로 서킷 브레이커 → 동시 호출 상한을 거친다(설정 기본값은 OrderDefaults). 재시도하지 않는다. 회로가 열렸거나
 *     상한이 찼거나 호출이 실패하면 기다리지 않고 캐시에 있는 것만 준다 — member 장애가 주문 목록을 막지 않는다.</li>
 * </ul>
 * 트랜잭션 밖(컨트롤러)에서 부른다 — DB 커넥션을 member 응답 시간만큼 붙잡지 않게.
 */
@Component
public class MemberNames {

    private static final Logger log = LoggerFactory.getLogger(MemberNames.class);

    /** 장애 대응 설정(resilience4j.*.instances.member)과 HTTP 클라이언트 그룹(spring.http.serviceclient.member)의 이름. */
    public static final String DEPENDENCY = "member";
    /** member 가 한 번에 받는 최대 개수(contracts/member-internal.md). */
    static final int MAX_IDS = 100;
    static final Duration TTL = Duration.ofMinutes(10);
    static final long MAXIMUM_SIZE = 10_000;

    private final MemberClient memberClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Cache<UUID, Optional<String>> names;

    public MemberNames(MemberClient memberClient, CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads) {
        this.memberClient = memberClient;
        this.circuitBreaker = circuitBreakers.circuitBreaker(DEPENDENCY);
        this.bulkhead = bulkheads.bulkhead(DEPENDENCY);
        this.names = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(MAXIMUM_SIZE).build();
    }

    /** 회원 id → 가린 이름. 없는 회원 · 받지 못한 회원은 빠진다. 예외를 던지지 않는다. */
    public Map<UUID, String> find(Collection<UUID> customerIds) {
        Map<UUID, String> found = new HashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(customerIds)) {
            Optional<String> cached = names.getIfPresent(id);
            if (cached == null) {
                missing.add(id);
            } else {
                cached.ifPresent(name -> found.put(id, name));
            }
        }
        for (int from = 0; from < missing.size(); from += MAX_IDS) {
            List<UUID> chunk = missing.subList(from, Math.min(from + MAX_IDS, missing.size()));
            if (!load(chunk, found)) {
                break;   // 실패한 뒤의 묶음도 같은 이유로 실패한다 — 더 기다리지 않는다
            }
        }
        return found;
    }

    /** @return 받았으면 true */
    private boolean load(List<UUID> ids, Map<UUID, String> found) {
        MemberClient.Names response;
        try {
            response = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Bulkhead.decorateSupplier(bulkhead, () -> memberClient.getNames(ids).data())).get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            log.warn("{} 이름 조회 안 함({}) — 이름 없이 응답한다", DEPENDENCY, e.getClass().getSimpleName());
            return false;
        } catch (RestClientException e) {
            // 시간 초과 · 연결 실패 · 4xx · 5xx · 읽을 수 없는 응답. 메시지는 싣지 않는다 — 요청 URL 전체(ids 최대 100개)가 든다
            log.warn("{} 이름 조회 실패 — 이름 없이 응답한다 ids={} {}", DEPENDENCY, ids.size(), describe(e));
            return false;
        } catch (RuntimeException e) {
            // 우리 쪽 결함(계약과 다른 응답 모양 등)이다. 이름 없이 응답하되 스택을 남긴다
            log.warn("{} 이름 조회 중 예상하지 못한 오류 — 이름 없이 응답한다 ids={}", DEPENDENCY, ids.size(), e);
            return false;
        }
        if (response == null || response.items() == null) {
            log.warn("{} 이름 조회 응답에 items 가 없다 — 이름 없이 응답한다", DEPENDENCY);
            return false;
        }
        Map<UUID, String> received = new HashMap<>();
        for (MemberClient.Name item : response.items()) {
            if (item != null && item.memberId() != null && item.name() != null && ids.contains(item.memberId())) {
                received.put(item.memberId(), item.name());
            }
        }
        for (UUID id : ids) {
            Optional<String> name = Optional.ofNullable(received.get(id));
            names.put(id, name);
            name.ifPresent(value -> found.put(id, value));
        }
        return true;
    }

    /** 예외 종류 · 상태 코드 · 원인 종류(시간 초과 · 연결 거절 구분). */
    private static String describe(RestClientException e) {
        String status = e instanceof RestClientResponseException response ? " " + response.getStatusCode().value() : "";
        Throwable cause = e.getCause();
        return e.getClass().getSimpleName() + status + (cause == null ? "" : " cause=" + cause.getClass().getSimpleName());
    }
}
