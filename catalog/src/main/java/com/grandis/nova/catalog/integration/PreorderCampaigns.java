package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.listing.CampaignWindow;
import com.grandis.nova.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 사전예약 회차 일정을 preorder 에 묻는다({@link PreorderCampaignClient}). catalog 는 preorder 의 회차 표를 읽지 않는다 — 회차 행이 있는가(사전예약의
 * 판매 준비)와 오픈 · 마감 시각은 모두 여기서 온다. 공개 여부 · 판매 중지는 preorder 가 거르지 않는다 — catalog 값으로 판단한다.
 *
 * <p>상품 내부 조회(1.3 · /internal/options)를 만드는 중에는 부르지 않는다 — preorder 접수가 그 응답을 동기로 부르므로 그 안에서 preorder 를 부르면
 * 접수 한 건이 preorder → catalog → preorder 로 서로를 기다린다.
 */
@Component
public class PreorderCampaigns {

    /** 한 번에 묻는 상품 수 — 계약 상한. */
    static final int BATCH = 100;
    /**
     * /open 응답을 쓸 수 있는 최대 나이. preorder 는 지금부터 10분 안에 열릴 회차까지 주고, 접수 중인지는 받는 쪽이 자기 시각으로 판정하므로 받은 뒤
     * 10분 동안은 오픈 · 마감 경계가 맞다(놓치는 것은 그사이의 조기 마감 · 다시 열기뿐). 그보다 오래되면 새로 열린 회차가 빠진다.
     * preorder 설정 nova.campaign.internal-lookahead(기본 10분)와 같아야 한다 — 응답에 실려 오지 않으므로 그 값을 줄이면 여기도 같이 줄인다.
     */
    static final Duration MAX_STALE = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(PreorderCampaigns.class);

    private final PreorderCampaignClient client;
    private final Clock clock;
    private final Duration refresh;
    private final ReentrantLock refreshing = new ReentrantLock();
    private volatile Snapshot last;
    /** 마지막으로 묻다가 실패한 시각 — 받는 중에는 적지 않는다(그동안 들어온 요청이 실패로 보고 빈 값을 받지 않게). */
    private volatile Instant lastFailure;

    /** @param cacheEnabled 읽기 캐시를 끄면(시험) /open 을 매번 새로 받는다. 켜면 5초 동안 받은 값을 쓴다 — 목록 읽기 캐시(5초)와 같은 수명 */
    @Autowired
    public PreorderCampaigns(PreorderCampaignClient client, Clock clock, @Value("${nova.read-cache.enabled:true}") boolean cacheEnabled) {
        this(client, clock, cacheEnabled ? Duration.ofSeconds(5) : Duration.ZERO);
    }

    PreorderCampaigns(PreorderCampaignClient client, Clock clock, Duration refresh) {
        this.client = client;
        this.clock = clock;
        this.refresh = refresh;
    }

    /**
     * 상품별 회차(2.2) — 지금 값. 캐시하지 않고 preorder 에도 캐시 없이(fresh) 묻는다 — 수정 · 삭제 · 등록 재전송 · 관리자 화면 · 일반 전환의 판정이
     * 그대로 쓴다. 공개 조회는 {@link PublicCampaigns} 를 거친다. 한 번에 {@value #BATCH}개씩 차례로 부른다.
     *
     * @return 상품 id → 회차. 회차가 없는 상품(판매 준비 전)은 빠진다. 빈 목록이면 부르지 않는다
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 연결 실패 · 시간 초과 · 5xx. INTERNAL_ERROR — 연동 오류(그 밖의 4xx · 읽을 수 없는 응답 ·
     *                           items 없음 · 묻지 않은 상품 · 겹친 상품 · 빈 시각, {@link InternalCalls})
     */
    public Map<UUID, CampaignWindow> find(Collection<UUID> productIds) {
        return find(productIds, true);
    }

    /**
     * 상품별 회차(2.2). @param fresh false 면 preorder 가 자기 회차 캐시로 답해도 된다(옛 값일 수 있다 — {@link PreorderCampaignClient#getCampaigns}).
     * 판정이 아니고 받은 값을 오래 두지 않는 호출만 false 로 부른다 — {@link PublicCampaigns} 가 캐시를 끈 조회와, 켰을 때 넣을 값인지 먼저 가리는 조회.
     * 나머지는 {@link #find(Collection)} 와 같다
     */
    public Map<UUID, CampaignWindow> find(Collection<UUID> productIds, boolean fresh) {
        Map<UUID, CampaignWindow> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(productIds));
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            PreorderCampaignClient.Campaigns campaigns = InternalCalls.call(Dependencies.PREORDER, () -> client.getCampaigns(batch, fresh));
            collect(campaigns.items(), batch, found);
        }
        return found;
    }

    /** 한 상품의 회차(2.2) — 지금 값. 없으면(판매 준비 전) 비어 있다. 실패는 {@link #find(Collection)} 와 같다. */
    public Optional<CampaignWindow> find(UUID productId) {
        return find(productId, true);
    }

    /** 한 상품의 회차(2.2). fresh 는 {@link #find(Collection, boolean)} 와 같다. */
    public Optional<CampaignWindow> find(UUID productId, boolean fresh) {
        return Optional.ofNullable(find(List.of(productId), fresh).get(productId));
    }

    /**
     * 마감 전이고 이미 열렸거나 곧 열릴 회차(2.1) — 회원 목록 · 검색 · 랭킹의 "접수 중" 조건과 카드의 시각. 접수 중인지는 호출자가 자기 시각으로 가른다.
     * 받은 값을 잠깐(읽기 캐시를 켜면 5초) 쓴다. preorder 가 답하지 않으면 다시 묻는 것도 그만큼 미루고, 그동안 마지막으로 받은 값을
     * {@link #MAX_STALE} 까지 쓴다. 그보다 오래됐거나 받은 적이 없으면 빈 값이다 — 목록은 사전예약 상품을 빼고 나간다(2026-10-19 결정).
     * 이미 누가 새로 받는 중이면 기다리지 않고 쓸 수 있는 마지막 값(없으면 빈 값)을 쓴다. 기다리는 것은 받은 적도 실패한 적도 없는 기동 직후뿐이다.
     */
    public Map<UUID, CampaignWindow> open() {
        Instant now = clock.instant();
        Snapshot current = last;
        if (current != null && fresh(current.fetchedAt(), now) || recentlyFailed(now)) {
            return usableOrEmpty(current, now);
        }
        if (!refreshing.tryLock()) {
            if (current != null || lastFailure != null) {
                return usableOrEmpty(current, now);   // 누가 새로 받는 중 — 기다리지 않는다(오래된 값이면 빈 값 — 장애 중 요청을 줄 세우지 않게)
            }
            refreshing.lock();   // 기동 직후 첫 조회 — 받는 쪽을 기다린다(빈 값으로 사전예약을 빼지 않게)
        }
        try {
            current = last;
            if (current != null && fresh(current.fetchedAt(), now) || recentlyFailed(now)) {
                return usableOrEmpty(current, now);
            }
            try {
                PreorderCampaignClient.OpenCampaigns open = InternalCalls.call(Dependencies.PREORDER, client::getOpen);
                Map<UUID, CampaignWindow> windows = new HashMap<>();
                collect(open.items(), null, windows);
                last = new Snapshot(Map.copyOf(windows), now);
                return last.windows();
            } catch (RuntimeException e) {
                lastFailure = now;
                if (!(e instanceof BusinessException)) {
                    throw e;   // 분류되지 않은 실패 — 다시 묻는 것은 미루되(위 실패 시각) 숨기지 않는다
                }
                // 원인 · 스택은 InternalCalls 가 남긴다
                log.warn("preorder 에 접수 중 회차를 묻지 못했다 — 마지막 값(받은 지 {} 이하)을 쓰고, 없으면 목록에서 사전예약을 뺀다: {}", MAX_STALE, e.toString());
                return usableOrEmpty(current, now);
            }
        } finally {
            refreshing.unlock();
        }
    }

    private boolean fresh(Instant fetchedAt, Instant now) {
        return now.isBefore(fetchedAt.plus(refresh));
    }

    /** 마지막 시도가 실패했고(그 뒤 받은 값이 없다) 아직 다시 물을 때가 아니다 — 장애 동안 요청마다 타임아웃을 기다리지 않게. */
    private boolean recentlyFailed(Instant now) {
        Instant failure = lastFailure;
        Snapshot current = last;
        boolean failed = failure != null && (current == null || current.fetchedAt().isBefore(failure));
        return failed && fresh(failure, now);
    }

    private static boolean usable(Snapshot snapshot, Instant now) {
        return !now.isAfter(snapshot.fetchedAt().plus(MAX_STALE));
    }

    private static Map<UUID, CampaignWindow> usableOrEmpty(Snapshot snapshot, Instant now) {
        return snapshot != null && usable(snapshot, now) ? snapshot.windows() : Map.of();
    }

    /** preorder 는 상품마다 한 번, 두 시각을 채워 답한다(계약) — 어긋나면 시각을 지어내지 않고 연동 오류로 본다. asked 가 null 이면 묻지 않은 상품을 보지 않는다. */
    private static void collect(List<PreorderCampaignClient.Campaign> items, List<UUID> asked, Map<UUID, CampaignWindow> into) {
        if (items == null) {
            // 빈 목록과 다르다 — "회차 없음"(판매 준비 전)으로 지어내지 않는다
            throw InternalCalls.contractViolation(Dependencies.PREORDER, "items 가 없는 응답");
        }
        for (PreorderCampaignClient.Campaign campaign : items) {
            if (campaign == null || campaign.productId() == null || campaign.opensAt() == null || campaign.closesAt() == null
                    || asked != null && !asked.contains(campaign.productId())
                    || into.put(campaign.productId(), new CampaignWindow(campaign.opensAt(), campaign.closesAt())) != null) {
                throw InternalCalls.contractViolation(Dependencies.PREORDER, "묻지 않은 상품 · 겹친 상품 · 빈 시각");
            }
        }
    }

    private record Snapshot(Map<UUID, CampaignWindow> windows, Instant fetchedAt) {
    }
}
